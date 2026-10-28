package com.sky.security;

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

    /**
     * 按"员工"维度的令牌作废时间戳。
     * 禁用账号时服务端手里并没有那个人的令牌原文，单令牌黑名单无法覆盖，
     * 因此记一个时间点：该时刻之前签发的令牌一律不再接受。
     */
    private static final String VALID_AFTER_PREFIX = "jwt:valid-after:emp:";

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
     * 让某位员工在 now 之前签发的所有令牌失效。
     * 保留时长取令牌的最大存活时间：之后这些令牌本身已经过期，记录就没有存在价值了。
     */
    public void revokeEmployee(Long empId, long ttlMillis) {
        if (empId == null) {
            return;
        }
        stringRedisTemplate.opsForValue().set(VALID_AFTER_PREFIX + empId,
                String.valueOf(System.currentTimeMillis()), Duration.ofMillis(Math.max(ttlMillis, 1000)));
    }

    /**
     * 令牌是否早于该员工的作废时间点签发。Redis 异常时按"有效"处理，
     * 与黑名单一致：这是登录态的增强校验，签名校验仍然照做，不能让一次抖动把全员踢下线。
     */
    public boolean isIssuedBeforeRevocation(Long empId, long issuedAtMillis) {
        if (empId == null) {
            return false;
        }
        try {
            String validAfter = stringRedisTemplate.opsForValue().get(VALID_AFTER_PREFIX + empId);
            return validAfter != null && Long.parseLong(validAfter) > issuedAtMillis;
        } catch (Exception e) {
            log.error("查询令牌作废时间点失败，本次按有效放行，请检查 Redis 可用性，empId={}", empId, e);
            return false;
        }
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
