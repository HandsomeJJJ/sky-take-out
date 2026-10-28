package com.sky.messaging.kafka;

import com.sky.event.OrderCreatedEvent;
import com.sky.messaging.MqMessageSender;
import com.sky.messaging.MessageTrace;
import com.sky.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;

/** 消费订单创建事件，异步累计每日下单量。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "sky.messaging.kafka", name = "enabled", havingValue = "true")
public class KafkaOrderStatisticsConsumer {

    /** 统计 key 按天分片，不设 TTL 就会永久累积 */
    private static final Duration STATISTICS_TTL = Duration.ofDays(180);

    private final StringRedisTemplate stringRedisTemplate;

    public KafkaOrderStatisticsConsumer(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @KafkaListener(topics = MqMessageSender.KAFKA_TOPIC_ORDER_CREATED, groupId = "sky-order-statistics")
    public void consume(ConsumerRecord<String, String> record) {
        MessageTrace.restore(headerValue(record));
        try {
            OrderCreatedEvent event = JsonUtils.toObject(record.value(), OrderCreatedEvent.class);
            if (event == null || event.getOrderId() == null) {
                throw new IllegalArgumentException("订单事件缺少 orderId");
            }
            // orderTime 为空原来会直接 NPE
            LocalDateTime orderTime = event.getOrderTime() == null ? LocalDateTime.now() : event.getOrderTime();

            String dedupKey = "order:statistics:processed:" + event.getOrderId();
            Boolean firstConsumed = stringRedisTemplate.opsForValue().setIfAbsent(dedupKey, "1", Duration.ofDays(8));
            if (!Boolean.TRUE.equals(firstConsumed)) {
                log.info("Kafka 重复订单事件已忽略，orderId={}", event.getOrderId());
                return;
            }

            String statisticsKey = "order:statistics:created:" + orderTime.toLocalDate();
            try {
                Long total = stringRedisTemplate.opsForValue().increment(statisticsKey);
                if (total != null && total == 1L) {
                    // 只在 key 首次生成时挂过期时间
                    stringRedisTemplate.expire(statisticsKey, STATISTICS_TTL);
                }
            } catch (RuntimeException e) {
                // 去重标记已经占了位，但计数没写进去：不把标记撤掉，重投就会被当成"已处理"直接忽略，
                // 这一单的统计就永久少算一次。撤掉标记再把异常交回错误处理器重试。
                releaseDedupKey(dedupKey);
                throw e;
            }
            log.info("订单异步统计完成，orderId={}, key={}", event.getOrderId(), statisticsKey);
        } catch (IllegalArgumentException e) {
            // 报文结构问题：重试也不会变好，交给不可重试清单直接进死信，日志留完整现场
            log.error("订单统计消息格式非法，转死信处理。topic={}, partition={}, offset={}, payload={}",
                    record.topic(), record.partition(), record.offset(), record.value(), e);
            throw e;
        } catch (RuntimeException e) {
            // 原来这里一律 catch 后只打日志：offset 照常提交，这条统计就永久丢了，也没人能从日志里补回来。
            // 现在往外抛，由 DefaultErrorHandler 按退避重试，重试用尽落 order.created.DLT。
            log.error("订单统计处理失败，交给错误处理器重试/转死信。topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset(), e);
            throw e;
        } finally {
            MessageTrace.clear();
        }
    }

    private void releaseDedupKey(String dedupKey) {
        try {
            stringRedisTemplate.delete(dedupKey);
        } catch (Exception e) {
            log.error("撤销统计去重标记失败，重投时该订单可能被当作重复消息跳过，key={}", dedupKey, e);
        }
    }

    private static String headerValue(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(MessageTrace.HEADER);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
