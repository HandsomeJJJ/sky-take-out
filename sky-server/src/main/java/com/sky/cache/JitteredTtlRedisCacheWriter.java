package com.sky.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 给缓存 TTL 加随机抖动的写器。
 * <p>
 * 统一固定 TTL 会让同一个缓存的所有 key 在同一时刻集体过期：过期后第一波请求全部打到
 * 数据库，这本身就是自己造的一次雪崩。把每个 key 的过期时间随机散开（±20%），
 * 过期就摊到一段时间里。
 * <p>
 * 只做包装，不改语义：get/remove/clean/putIfAbsent 全部原样委托，
 * 其中 putIfAbsent 的返回值必须透传，否则 {@code @Cacheable(sync = true)} 的互斥回源就失效了。
 */
@Slf4j
public class JitteredTtlRedisCacheWriter implements RedisCacheWriter {

    /** 抖动比例：TTL 在原值的 ±20% 之间浮动 */
    private static final double JITTER_RATIO = 0.2;

    private final RedisCacheWriter delegate;

    public JitteredTtlRedisCacheWriter(RedisCacheWriter delegate) {
        this.delegate = delegate;
    }

    /**
     * 用 locking 写器做底层：{@code @Cacheable(sync = true)} 靠它的 putIfAbsent + 自旋锁
     * 保证同一个 key 只有一个线程回源，其余线程等结果，而不是全部涌向数据库。
     */
    public static JitteredTtlRedisCacheWriter locking(RedisConnectionFactory connectionFactory) {
        return new JitteredTtlRedisCacheWriter(RedisCacheWriter.lockingRedisCacheWriter(connectionFactory));
    }

    @Override
    public void put(String name, byte[] key, byte[] value, Duration ttl) {
        delegate.put(name, key, value, jitter(ttl));
    }

    @Override
    public byte[] get(String name, byte[] key) {
        return delegate.get(name, key);
    }

    @Override
    public byte[] putIfAbsent(String name, byte[] key, byte[] value, Duration ttl) {
        return delegate.putIfAbsent(name, key, value, jitter(ttl));
    }

    @Override
    public void remove(String name, byte[] pattern) {
        delegate.remove(name, pattern);
    }

    @Override
    public void clean(String name, byte[] pattern) {
        delegate.clean(name, pattern);
    }

    @Override
    public void clearStatistics(String name) {
        delegate.clearStatistics(name);
    }

    @Override
    public RedisCacheWriter withStatisticsCollector(CacheStatisticsCollector cacheStatisticsCollector) {
        // 委托而不是返回 this：统计收集器要落到真正的写器上，否则命中率统计恒为 0
        return new JitteredTtlRedisCacheWriter(delegate.withStatisticsCollector(cacheStatisticsCollector));
    }

    @Override
    public CacheStatistics getCacheStatistics(String cacheName) {
        return delegate.getCacheStatistics(cacheName);
    }

    /**
     * ttl 为空或非正数时原样返回：RedisCacheConfiguration 用 0 表示"不过期"，
     * 把它随机化成一个负数会直接抛异常。
     */
    static Duration jitter(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return ttl;
        }
        long base = ttl.toMillis();
        long spread = (long) (base * JITTER_RATIO);
        if (spread <= 0) {
            return ttl;
        }
        long offset = ThreadLocalRandom.current().nextLong(-spread, spread + 1);
        long jittered = base + offset;
        return Duration.ofMillis(Math.max(jittered, 1000));
    }
}
