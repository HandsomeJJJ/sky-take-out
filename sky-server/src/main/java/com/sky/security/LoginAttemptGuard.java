package com.sky.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

/**
 * 登录失败计数与临时锁定。
 * <p>
 * 撞库/暴力破解的门槛原来只有一句"密码错误"：同一个账号可以无限次试。
 * 这里按账号维度累计失败次数，达到阈值就把账号临时锁一小段时间。
 * <p>
 * 只对"账号存在但密码错"计数：账号不存在时不计数，否则任何人用"用户名 + 乱密码"
 * 就能把真实用户的账号锁死，限流反而变成攻击面。账号不存在的探测交给接口级 IP 限流。
 */
@Component
@Slf4j
public class LoginAttemptGuard {

    /** 连续失败次数阈值 */
    private static final int MAX_FAILED_ATTEMPTS = 5;

    /** 计数窗口，同时也是锁定时长 */
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private static final String COUNTER_PREFIX = "login:fail:";
    private static final String LOCK_PREFIX = "login:lock:";

    /** 计数 +1，首次时设置过期；到阈值就写锁定键并清掉计数，两条操作在 Lua 里原子完成 */
    private static final DefaultRedisScript<Long> RECORD_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1])\n" +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n" +
            "if c >= tonumber(ARGV[2]) then\n" +
            "  redis.call('SET', KEYS[2], c, 'EX', ARGV[1])\n" +
            "  redis.call('DEL', KEYS[1])\n" +
            "end\n" +
            "return c", Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public LoginAttemptGuard(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean isLocked(String username) {
        if (!StringUtils.hasText(username)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(LOCK_PREFIX + username));
        } catch (Exception e) {
            // Redis 不可用时放行登录：鉴权本身仍然执行，不能因为一次抖动把所有人都关在门外
            log.error("查询登录锁定状态失败，本次按未锁定放行", e);
            return false;
        }
    }

    /**
     * 记一次密码错误。
     *
     * @return 是否已经因为这次失败而触发锁定
     */
    public boolean recordFailure(String username) {
        if (!StringUtils.hasText(username)) {
            return false;
        }
        try {
            Long count = stringRedisTemplate.execute(RECORD_SCRIPT,
                    Arrays.asList(COUNTER_PREFIX + username, LOCK_PREFIX + username),
                    String.valueOf(WINDOW.getSeconds()), String.valueOf(MAX_FAILED_ATTEMPTS));
            boolean locked = count != null && count >= MAX_FAILED_ATTEMPTS;
            if (locked) {
                log.warn("账号 {} 连续 {} 次密码错误，临时锁定 {} 分钟",
                        username, MAX_FAILED_ATTEMPTS, WINDOW.toMinutes());
            }
            return locked;
        } catch (Exception e) {
            log.error("记录登录失败次数出错，不影响本次登录结果", e);
            return false;
        }
    }

    /** 登录成功后清零，避免把正常使用的人累积到阈值 */
    public void clear(String username) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        try {
            stringRedisTemplate.delete(Collections.singletonList(COUNTER_PREFIX + username));
        } catch (Exception e) {
            log.error("清理登录失败计数出错，username={}", username, e);
        }
    }

    public static int maxFailedAttempts() {
        return MAX_FAILED_ATTEMPTS;
    }

    public static Duration lockWindow() {
        return WINDOW;
    }
}
