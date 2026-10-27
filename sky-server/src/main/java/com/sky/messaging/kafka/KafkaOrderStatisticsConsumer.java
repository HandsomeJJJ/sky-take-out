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
            Long total = stringRedisTemplate.opsForValue().increment(statisticsKey);
            if (total != null && total == 1L) {
                // 只在 key 首次生成时挂过期时间
                stringRedisTemplate.expire(statisticsKey, STATISTICS_TTL);
            }
            log.info("订单异步统计完成，orderId={}, key={}", event.getOrderId(), statisticsKey);
        } catch (Exception e) {
            // 毒丸消息不往外抛：抛出去只会让错误处理器反复重试同一条，跳过后由日志告警捞取
            log.error("订单统计消息处理失败，跳过。topic={}, partition={}, offset={}, payload={}",
                    record.topic(), record.partition(), record.offset(), record.value(), e);
        } finally {
            MessageTrace.clear();
        }
    }

    private static String headerValue(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(MessageTrace.HEADER);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
