package com.sky.messaging.rabbitmq;

import com.rabbitmq.client.Channel;
import com.sky.event.OrderCreatedEvent;
import com.sky.mapper.OrderMapper;
import com.sky.messaging.MessageTrace;
import com.sky.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 延迟消息到期后取消仍待支付的订单，条件更新保证消费幂等。
 * <p>
 * 手动 ACK：解析失败或业务异常一律 nack 且不 requeue，进死信队列等人工处理。
 * 自动 ACK 下坏消息会被无限重投，把消费者线程占死。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "sky.messaging.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitOrderTimeoutConsumer {

    private final OrderMapper orderMapper;

    public RabbitOrderTimeoutConsumer(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @RabbitListener(queues = RabbitMqConfiguration.CANCEL_QUEUE)
    public void cancelIfUnpaid(String payload,
                               Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                               @Header(name = MessageTrace.HEADER, required = false) String incomingTraceId)
            throws IOException {
        String traceId = MessageTrace.restore(incomingTraceId);
        try {
            OrderCreatedEvent event = JsonUtils.toObject(payload, OrderCreatedEvent.class);
            if (event == null || event.getOrderId() == null) {
                throw new IllegalArgumentException("超时取消消息缺少 orderId");
            }

            int affectedRows = orderMapper.cancelPendingPaymentById(event.getOrderId(),
                    "订单超时，自动取消", LocalDateTime.now());
            log.info("订单超时检查完成，orderId={}, cancelled={}", event.getOrderId(), affectedRows == 1);

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // requeue=false：重新入队只会让同一条坏消息反复撞同一个消费者
            log.error("订单超时消息处理失败，转入死信队列。payload={}", payload, e);
            channel.basicNack(deliveryTag, false, false);
        } finally {
            MessageTrace.clear();
        }
    }
}
