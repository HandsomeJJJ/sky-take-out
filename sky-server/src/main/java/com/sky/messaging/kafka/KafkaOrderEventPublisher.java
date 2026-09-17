package com.sky.messaging.kafka;

import com.alibaba.fastjson.JSON;
import com.sky.event.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 将已提交的订单事件异步投递到 Kafka。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "sky.messaging.kafka", name = "enabled", havingValue = "true")
public class KafkaOrderEventPublisher {
    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaOrderEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @TransactionalEventListener
    public void publish(OrderCreatedEvent event) {
        kafkaTemplate.send("order.created", event.getOrderId().toString(), JSON.toJSONString(event));
        log.info("订单创建事件已投递 Kafka，orderId={}", event.getOrderId());
    }
}
