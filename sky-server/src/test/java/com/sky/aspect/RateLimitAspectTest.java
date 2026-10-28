package com.sky.aspect;

import com.sky.annotation.RateLimit;
import com.sky.context.BaseContext;
import com.sky.exception.RateLimitException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 滑动窗口限流切面。
 * <p>
 * 锁两件事：Lua 返回的等待毫秒数被翻译成 429 语义（RetryAfterSeconds），
 * 以及限流维度是"登录用户 / 客户端 IP"而不是一个全局 anon 桶。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitAspectTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ProceedingJoinPoint joinPoint;
    @Mock
    private MethodSignature methodSignature;

    private RateLimitAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new RateLimitAspect(stringRedisTemplate);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("窗口内未超限：脚本返回 0，直接放行")
    void allowsRequestWithinWindow() throws Throwable {
        stubScript(0L);

        Object result = aspect.around(joinPoint, rateLimit(5, 60, "submitOrder"));

        assertThat(result).isNull();
        verify(joinPoint).proceed();
    }

    @Test
    @DisplayName("超限：按脚本给出的等待时间抛 RateLimitException，不再执行业务方法")
    void rejectsWhenOverLimit() throws Throwable {
        stubScript(1500L);

        assertThatThrownBy(() -> aspect.around(joinPoint, rateLimit(5, 60, "submitOrder")))
                .isInstanceOf(RateLimitException.class)
                .hasFieldOrPropertyWithValue("retryAfterSeconds", 2L);

        verify(joinPoint, never()).proceed();
    }

    @Test
    @DisplayName("Redis 抖动时限流放行并打 error：增强校验不该把业务接口全打挂")
    void failsOpenWhenRedisUnavailable() throws Throwable {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThatCode(() -> aspect.around(joinPoint, rateLimit(5, 60, "submitOrder")))
                .doesNotThrowAnyException();

        verify(joinPoint).proceed();
    }

    @Test
    @DisplayName("已登录按用户维度限流，key 形如 rate:limit:submitOrder:uid:7")
    void keysByUserWhenLoggedIn() throws Throwable {
        BaseContext.setCurrentId(7L);
        stubScript(0L);

        aspect.around(joinPoint, rateLimit(5, 60, "submitOrder"));

        assertThat(capturedKey()).isEqualTo("rate:limit:submitOrder:uid:7");
    }

    @Test
    @DisplayName("未登录按客户端 IP 维度限流，不再共用一个 anon 桶")
    void keysByClientIpWhenAnonymous() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.8");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        stubScript(0L);

        aspect.around(joinPoint, rateLimit(5, 60, "submitOrder"));

        assertThat(capturedKey()).isEqualTo("rate:limit:submitOrder:ip:10.0.0.8");
    }

    @Test
    @DisplayName("未指定 key 时取方法名，避免不同接口共用同一个计数器")
    void fallsBackToMethodName() throws Throwable {
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(TestEndpoints.class.getMethod("checkout"));
        stubScript(0L);

        aspect.around(joinPoint, rateLimit(5, 60, ""));

        assertThat(capturedKey()).startsWith("rate:limit:checkout:");
    }

    @SuppressWarnings("unchecked")
    private void stubScript(Long result) {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(),
                any(), any(), any(), any())).thenReturn(result);
    }

    @SuppressWarnings("unchecked")
    private String capturedKey() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), captor.capture(),
                any(), any(), any(), any());
        return captor.getValue().get(0);
    }

    /** 注解在测试里直接实现，避免为了造实例而反射动态代理 */
    private static RateLimit rateLimit(int limit, int window, String key) {
        return new RateLimit() {
            @Override
            public int limit() {
                return limit;
            }

            @Override
            public int window() {
                return window;
            }

            @Override
            public String key() {
                return key;
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return RateLimit.class;
            }
        };
    }

    static class TestEndpoints {
        public void checkout() {
        }
    }
}
