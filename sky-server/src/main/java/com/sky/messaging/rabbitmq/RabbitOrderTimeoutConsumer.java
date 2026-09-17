package com.sky.messaging.rabbitmq;

import com.alibaba.fastjson.JSON;
import com.sky.event.OrderCreatedEvent;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 延迟消息到期后仅取消仍待支付的订单，条件更新保证消费幂等。 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "sky.messaging.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitOrderTimeoutConsumer {
    private final OrderMapper orderMapper;

    public RabbitOrderTimeoutConsumer(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @RabbitListener(queues = RabbitMqConfiguration.CANCEL_QUEUE)
    public void cancelIfUnpaid(String payload) {
        OrderCreatedEvent event = JSON.parseObject(payload, OrderCreatedEvent.class);
        int affectedRows = orderMapper.cancelPendingPaymentById(event.getOrderId(),
                "订单超时，自动取消", LocalDateTime.now());
        log.info("订单超时检查完成，orderId={}, cancelled={}", event.getOrderId(), affectedRows == 1);
    }
}
