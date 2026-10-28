package com.sky.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存 TTL 抖动：只测"散得开"和"委托不破"两件事。
 * sync=true 的互斥语义依赖 putIfAbsent 的返回值原样透传，这条也一并锁住。
 */
@ExtendWith(MockitoExtension.class)
class JitteredTtlRedisCacheWriterTest {

    private static final Duration BASE = Duration.ofMinutes(60);

    @Mock
    private RedisCacheWriter delegate;

    @Test
    @DisplayName("TTL 在基准值的 ±20% 内散开，不会所有 key 同时过期")
    void spreadsTtlWithinTwentyPercent() {
        JitteredTtlRedisCacheWriter writer = new JitteredTtlRedisCacheWriter(delegate);

        for (int i = 0; i < 200; i++) {
            Duration jittered = JitteredTtlRedisCacheWriter.jitter(BASE);
            assertThat(jittered).isBetween(BASE.multipliedBy(80).dividedBy(100),
                    BASE.multipliedBy(120).dividedBy(100));
        }

        // 200 次里如果只出现一两个取值，说明抖动实际上是常量，起不到摊开过期的作用
        long distinct = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> JitteredTtlRedisCacheWriter.jitter(BASE))
                .distinct().count();
        assertThat(distinct).isGreaterThan(50);
    }

    @Test
    @DisplayName("0/负数/空 TTL 原样返回：这些值表示不过期，随机化会抛异常")
    void keepsNonPositiveTtlUntouched() {
        assertThat(JitteredTtlRedisCacheWriter.jitter(null)).isNull();
        assertThat(JitteredTtlRedisCacheWriter.jitter(Duration.ZERO)).isEqualTo(Duration.ZERO);
        assertThat(JitteredTtlRedisCacheWriter.jitter(Duration.ofSeconds(-5)))
                .isEqualTo(Duration.ofSeconds(-5));
    }

    @Test
    @DisplayName("put 把抖动后的 TTL 交给底层写器")
    void putsWithJitteredTtl() {
        JitteredTtlRedisCacheWriter writer = new JitteredTtlRedisCacheWriter(delegate);
        byte[] key = "dishCache::1".getBytes();
        byte[] value = "{}".getBytes();

        writer.put("dishCache", key, value, BASE);

        ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
        verify(delegate).put(eq("dishCache"), eq(key), eq(value), captor.capture());
        assertThat(captor.getValue()).isBetween(Duration.ofMinutes(48), Duration.ofMinutes(72));
    }

    @Test
    @DisplayName("putIfAbsent 的返回值必须透传，否则 sync=true 的回源互斥失效")
    void passesPutIfAbsentResultThrough() {
        byte[] existing = "old".getBytes();
        when(delegate.putIfAbsent(any(), any(), any(), any())).thenReturn(existing);
        JitteredTtlRedisCacheWriter writer = new JitteredTtlRedisCacheWriter(delegate);

        byte[] result = writer.putIfAbsent("dishCache", "k".getBytes(), "v".getBytes(), BASE);

        assertThat(result).isSameAs(existing);
    }

    @Test
    @DisplayName("统计收集器落到真正的写器上，而不是被包装类吞掉")
    void forwardsStatisticsCollector() {
        RedisCacheWriter collected = org.mockito.Mockito.mock(RedisCacheWriter.class);
        when(delegate.withStatisticsCollector(any())).thenReturn(collected);
        JitteredTtlRedisCacheWriter writer = new JitteredTtlRedisCacheWriter(delegate);

        RedisCacheWriter decorated = writer.withStatisticsCollector(
                org.mockito.Mockito.mock(CacheStatisticsCollector.class));

        assertThat(decorated).isInstanceOf(JitteredTtlRedisCacheWriter.class);
        verify(delegate).withStatisticsCollector(any());
    }
}
