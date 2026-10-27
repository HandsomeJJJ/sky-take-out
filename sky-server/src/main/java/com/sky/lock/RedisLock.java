package com.sky.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 的分布式锁。
 *
 * 相比自研的 SET NX EX + Lua，Redisson 额外提供：
 * 1. watchdog 看门狗自动续期：leaseTime 传 -1 时，锁默认 30s，业务执行期间每 10s 自动续期，
 *    不会因为业务超时导致锁提前失效；进程崩溃后 watchdog 停止，30s 后锁自动释放，不会死锁；
 * 2. 可重入：同一线程可重复加锁；
 * 3. 公平锁 / 联锁 / 红锁等更多原语。
 */
@Component
public class RedisLock {

    private final RedissonClient redissonClient;

    public RedisLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试获取锁，最多等待 waitTime 毫秒。
     *
     * @param key      锁的 key
     * @param waitTime 最长等待时间，传 Duration.ZERO 表示不等待立即返回
     * @return 是否获取到锁
     */
    public boolean tryLock(String key, Duration waitTime) {
        RLock lock = redissonClient.getLock(key);
        try {
            // leaseTime=-1 启用 watchdog 自动续期
            return lock.tryLock(waitTime.toMillis(), -1, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 释放锁。仅当锁被当前线程持有时才释放，避免误删。
     */
    public void unlock(String key) {
        RLock lock = redissonClient.getLock(key);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
