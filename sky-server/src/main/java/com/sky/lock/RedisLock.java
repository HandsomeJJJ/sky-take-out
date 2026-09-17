package com.sky.lock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/** 基于 SET NX EX 的锁；Lua 脚本保证比较令牌和删除是原子操作。 */
@Component
public class RedisLock {
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private final StringRedisTemplate stringRedisTemplate;

    public RedisLock(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 返回锁令牌；未获取锁时返回 null。 */
    public String tryLock(String key, Duration timeout) {
        String token = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(key, token, timeout);
        return Boolean.TRUE.equals(locked) ? token : null;
    }

    public void unlock(String key, String token) {
        if (token != null) {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        }
    }
}
