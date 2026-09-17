package com.sky.messaging.kafka;

import com.alibaba.fastjson.JSON;
import com.sky.event.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 消费订单创建事件，异步累计每日下单量。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "sky.messaging.kafka", name = "enabled", havingValue = "true")
public class KafkaOrderStatisticsConsumer {
    private final StringRedisTemplate stringRedisTemplate;

    public KafkaOrderStatisticsConsumer(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @KafkaListener(topics = "order.created", groupId = "sky-order-statistics")
    public void consume(String payload) {
        OrderCreatedEvent event = JSON.parseObject(payload, OrderCreatedEvent.class);
        String dedupKey = "order:statistics:processed:" + event.getOrderId();
        Boolean firstConsumed = stringRedisTemplate.opsForValue().setIfAbsent(dedupKey, "1", Duration.ofDays(8));
        if (!Boolean.TRUE.equals(firstConsumed)) {
            log.info("Kafka 重复订单事件已忽略，orderId={}", event.getOrderId());
            return;
        }
        String statisticsKey = "order:statistics:created:" + event.getOrderTime().toLocalDate();
        stringRedisTemplate.opsForValue().increment(statisticsKey);
        log.info("订单异步统计完成，orderId={}, key={}", event.getOrderId(), statisticsKey);
    }
}
