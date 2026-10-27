package com.sky.security;

import lombok.extern.slf4j.Slf4j;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * 已登出令牌黑名单。
 * <p>
 * JWT 是无状态的，服务端删不掉已经签出去的 token；不做黑名单的话
 * logout 之后原 token 在有效期内照样能用，令牌泄露也无从撤销。
 * 这里只登记"这个 token 不再被接受"，到期自动清理。
 */
@Component
@Slf4j
public class TokenBlacklist {

    private static final String KEY_PREFIX = "jwt:revoked:";

    private final StringRedisTemplate stringRedisTemplate;

    public TokenBlacklist(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 登记失效令牌。
     *
     * @param ttlMillis 该令牌签发端配置的最大存活时间，作为黑名单保留时长
     */
    public void revoke(String token, long ttlMillis) {
        if (!StringUtils.hasText(token)) {
            return;
        }
        stringRedisTemplate.opsForValue().set(key(token), "1", Duration.ofMillis(Math.max(ttlMillis, 1000)));
    }

    /**
     * Redis 不可用时返回"未登出"并打错误日志。
     * 黑名单是登录态的增强校验，不是鉴权本身（签名校验仍然执行）；
     * 让它异常直接冒到拦截器的 catch 里，等于一次 Redis 抖动把所有持令牌请求变成 401。
     */
    public boolean isRevoked(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key(token)));
        } catch (Exception e) {
            log.error("查询令牌黑名单失败，本次按未登出放行，请检查 Redis 可用性", e);
            return false;
        }
    }

    /**
     * 用摘要做 key，避免完整的、仍然可用的令牌原样落在 Redis 里。
     */
    private String key(String token) {
        return KEY_PREFIX + sha256Hex(token);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
