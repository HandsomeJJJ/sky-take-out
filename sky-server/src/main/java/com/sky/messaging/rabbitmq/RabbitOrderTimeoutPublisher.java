package com.sky.messaging.rabbitmq;

import com.alibaba.fastjson.JSON;
import com.sky.event.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 订单提交成功后写入延迟队列，15 分钟后触发取消检查。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "sky.messaging.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitOrderTimeoutPublisher {
    private final RabbitTemplate rabbitTemplate;

    public RabbitOrderTimeoutPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener
    public void publish(OrderCreatedEvent event) {
        rabbitTemplate.convertAndSend(RabbitMqConfiguration.DELAY_EXCHANGE,
                RabbitMqConfiguration.ROUTING_KEY, JSON.toJSONString(event));
        log.info("订单超时检查消息已投递，orderId={}", event.getOrderId());
    }
}
