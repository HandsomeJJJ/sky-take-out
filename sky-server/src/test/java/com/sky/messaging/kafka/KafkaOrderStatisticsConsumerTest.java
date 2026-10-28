package com.sky.messaging.kafka;

import com.sky.event.OrderCreatedEvent;
import com.sky.utils.JsonUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单统计消费的失败语义。
 * <p>
 * 原来这里 catch 全部异常只打日志，offset 照常提交 —— 这条统计就永久丢了，
 * 谁也没法从日志里把它补回来。现在异常必须交回容器（由 DefaultErrorHandler 退避重试、
 * 用尽后落 order.created.DLT），并且撤掉去重标记，否则重投会被当作"已处理"跳过。
 */
@ExtendWith(MockitoExtension.class)
class KafkaOrderStatisticsConsumerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private KafkaOrderStatisticsConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new KafkaOrderStatisticsConsumer(stringRedisTemplate);
    }

    @Test
    @DisplayName("重复投递被去重标记挡住，不再累计第二次")
    void ignoresDuplicateEvent() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("order:statistics:processed:1"), anyString(), any()))
                .thenReturn(false);

        consumer.consume(record(event(1L, LocalDateTime.of(2026, 10, 28, 12, 0))));

        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    @DisplayName("首次消费累计当日下单量，key 按天分片")
    void countsPerDay() {
        stubFirstConsumption(3L);
        when(valueOperations.increment("order:statistics:created:2026-10-28")).thenReturn(3L);

        consumer.consume(record(event(7L, LocalDateTime.of(2026, 10, 28, 12, 0))));

        verify(valueOperations).increment("order:statistics:created:2026-10-28");
        // 不是当天第一个计数就不要重复设置过期时间
        verify(stringRedisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("当日第一次计数才挂 TTL，避免统计 key 永久累积")
    void setsTtlOnFirstCountOnly() {
        stubFirstConsumption(1L);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        consumer.consume(record(event(7L, LocalDateTime.of(2026, 10, 28, 12, 0))));

        verify(stringRedisTemplate).expire(eq("order:statistics:created:2026-10-28"), any(Duration.class));
    }

    @Test
    @DisplayName("Redis 写入失败：撤销去重标记并把异常交回容器重试")
    void releasesDedupKeyAndRethrowsWhenCountingFails() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(valueOperations.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        when(stringRedisTemplate.delete(anyString())).thenReturn(true);

        assertThatThrownBy(() -> consumer.consume(
                record(event(8L, LocalDateTime.of(2026, 10, 28, 12, 0)))))
                .isInstanceOf(RedisConnectionFailureException.class);

        // 顺序很重要：先撤标记，重投时才不会被当作"已处理"跳过
        verify(stringRedisTemplate).delete("order:statistics:processed:8");
    }

    @Test
    @DisplayName("报文缺 orderId 属于不可重试错误：抛出去，由错误处理器直接转死信")
    void rethrowsPoisonMessage() {
        assertThatThrownBy(() -> consumer.consume(new ConsumerRecord<>("order.created", 0, 0L, null, "{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("orderTime 为空不再 NPE，退化成当天")
    void toleratesMissingOrderTime() {
        stubFirstConsumption(1L);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        OrderCreatedEvent event = event(9L, null);

        consumer.consume(record(event));

        verify(valueOperations).increment(anyString());
    }

    private void stubFirstConsumption(Long incrementResult) {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(valueOperations.increment(anyString())).thenReturn(incrementResult);
    }

    private static OrderCreatedEvent event(Long orderId, LocalDateTime orderTime) {
        return new OrderCreatedEvent(orderId, "20261028000" + orderId, orderId,
                new BigDecimal("36.00"), orderTime);
    }

    private static ConsumerRecord<String, String> record(OrderCreatedEvent event) {
        return new ConsumerRecord<>("order.created", 0, 0L, null, JsonUtils.toJson(event));
    }
}
