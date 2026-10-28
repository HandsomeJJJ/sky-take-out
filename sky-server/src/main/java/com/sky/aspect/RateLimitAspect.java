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
import java.util.UUID;

/**
 * 接口限流切面：Redis 滑动窗口。
 * <p>
 * 之前是固定窗口计数器：窗口边界可以放过两倍的流量（前一窗口末尾 + 后一窗口开头），
 * 对下单这种接口就是"限流形同虚设的一瞬间"。改成滑动窗口日志后，
 * 任意长度为一个窗口的区间内通过的请求数都不会超过 limit。
 * <p>
 * 所有操作都在一段 Lua 里完成：清理过期记录、计数、写入、续期必须原子，
 * 否则并发线程各自读到"还差一个"，就都放行。
 */
@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    /**
     * KEYS[1]=计数用 ZSET；ARGV = 当前毫秒、窗口秒、上限、本次成员。
     * 返回 0 表示放行；返回正数表示需要等待的毫秒数（取最早那条记录滑出窗口的时间）。
     */
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>(
            "local now = tonumber(ARGV[1])\n" +
            "local window = tonumber(ARGV[2]) * 1000\n" +
            "local limit = tonumber(ARGV[3])\n" +
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)\n" +
            "local count = redis.call('ZCARD', KEYS[1])\n" +
            "if count < limit then\n" +
            "  redis.call('ZADD', KEYS[1], now, ARGV[4])\n" +
            "  redis.call('PEXPIRE', KEYS[1], window)\n" +
            "  return 0\n" +
            "end\n" +
            "local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')\n" +
            "local wait = window\n" +
            "if oldest[2] then wait = (tonumber(oldest[2]) + window) - now end\n" +
            "if wait < 0 then wait = 0 end\n" +
            "return wait",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public RateLimitAspect(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        String key = buildKey(pjp, rateLimit);
        long now = System.currentTimeMillis();
        Long waitMillis;
        try {
            waitMillis = stringRedisTemplate.execute(SLIDING_WINDOW_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(now),
                    String.valueOf(rateLimit.window()),
                    String.valueOf(rateLimit.limit()),
                    now + "-" + UUID.randomUUID());
        } catch (Exception e) {
            // Redis 抖动时放行：限流是增强校验，不是鉴权本身。
            // 让它 fail-closed 等于"Redis 一抖，下单/登录全部 500"，比被刷更糟。
            // 与令牌黑名单、营业状态回源的 fail-open 口径保持一致。
            log.error("限流计数读取失败，本次放行，key={}", key, e);
            return pjp.proceed();
        }

        if (waitMillis != null && waitMillis > 0) {
            long retryAfterSeconds = Math.max(1, (waitMillis + 999) / 1000);
            log.warn("触发限流，key={}, limit={}/{}s，建议 {} 秒后重试",
                    key, rateLimit.limit(), rateLimit.window(), retryAfterSeconds);
            throw new RateLimitException("请求过于频繁，请稍后再试", retryAfterSeconds);
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
