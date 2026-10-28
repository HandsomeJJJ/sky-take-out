package com.sky.aspect;

import com.sky.annotation.RateLimit;
import com.sky.context.BaseContext;
import com.sky.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;

/**
 * 接口限流切面：固定窗口限流。
 * 用 Lua 脚本保证 INCR + EXPIRE 原子执行（两条命令分开写的话，进程在中间挂了 key 就永不过期）。
 */
@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    /** 原子：计数器 +1；首次访问时设置窗口过期时间 */
    private static final DefaultRedisScript<Long> LIMIT_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1])\n" +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n" +
            "return c",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public RateLimitAspect(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        String key = buildKey(pjp, rateLimit);
        Long count = stringRedisTemplate.execute(LIMIT_SCRIPT,
                Collections.singletonList(key), String.valueOf(rateLimit.window()));
        if (count != null && count > rateLimit.limit()) {
            log.warn("触发限流，key={}, count={}, limit={}", key, count, rateLimit.limit());
            throw new RateLimitException("请求过于频繁，请稍后再试");
        }
        return pjp.proceed();
    }

    private String buildKey(ProceedingJoinPoint pjp, RateLimit rateLimit) {
        String prefix = rateLimit.key();
        if (StringUtils.isEmpty(prefix)) {
            prefix = ((MethodSignature) pjp.getSignature()).getMethod().getName();
        }
        // 已登录按用户维度限流；未登录（登录接口本身）按客户端 IP 维度。
        // 原来未登录全部归到一个 anon 桶：一个 NAT 出口能让所有真实用户一起被限，
        // 而登录口共用一个桶也起不到"按来源节流爆破"的作用。
        Long userId = BaseContext.getCurrentId();
        String principal = userId == null ? "ip:" + clientIp() : "uid:" + userId;
        return "rate:limit:" + prefix + ":" + principal;
    }

    /**
     * 取请求来源地址。只信 TCP 层的 remoteAddr：X-Forwarded-For 是客户端可自填的头，
     * 拿它做限流维度等于把限流开关交给攻击者。前面确实挂了可信代理时，
     * 应在代理层配置可信跳数后再改这里。
     */
    private String clientIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        String remote = attributes.getRequest().getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
