package com.sky.messaging.rabbitmq;

import com.rabbitmq.client.Channel;
import com.sky.entity.OrderNotification;
import com.sky.event.OrderCreatedEvent;
import com.sky.mapper.OrderMapper;
import com.sky.messaging.MessageTrace;
import com.sky.service.OrderNotificationService;
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
 * 死信队列消费者。
 * <p>
 * 之前只声明了 DLQ，没有任何地方消费它：取消消息因为异常进死信之后，那笔订单就**永远停在待支付**，
 * 而"MQ 已启用"还会让兜底扫描任务不创建 —— 死信队列于是从"最后一道保险"变成了"黑洞"。
 * <p>
 * 这里做两件事：能识别的订单立刻用条件更新补做超时取消（与主消费者同一套幂等口径），
 * 识别不了的（报文坏了/未知类型）打 error 留下可被告警捞出来的痕迹。
 * 无论补做成败都 ack：死信已经是这条消息的最后一站，不 ack 只会让它反复回到同一个队列。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "sky.messaging.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitOrderTimeoutDeadLetterConsumer {

    private final OrderMapper orderMapper;
    private final OrderNotificationService notificationService;

    public RabbitOrderTimeoutDeadLetterConsumer(OrderMapper orderMapper,
                                                OrderNotificationService notificationService) {
        this.orderMapper = orderMapper;
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = RabbitMqConfiguration.CANCEL_DLQ)
    public void onDeadLetter(String payload,
                             Channel channel,
                             @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                             @Header(name = MessageTrace.HEADER, required = false) String incomingTraceId)
            throws IOException {
        MessageTrace.restore(incomingTraceId);
        try {
            log.error("订单超时消息进入死信队列，本次由死信消费者补做，payload={}", payload);
            OrderCreatedEvent event = JsonUtils.toObject(payload, OrderCreatedEvent.class);
            if (event == null || event.getOrderId() == null) {
                // 报文本身不可恢复，重投多少次都一样：留 error 痕迹交人工/告警
                log.error("死信消息无法解析，需要人工处理。payload={}", payload);
                return;
            }
            int rows = orderMapper.cancelPendingPaymentById(event.getOrderId(),
                    "订单超时，自动取消（死信补做）", LocalDateTime.now());
            log.warn("死信补做超时取消完成，orderId={}, cancelled={}", event.getOrderId(), rows == 1);
            if (rows == 1) {
                notificationService.notify(event.getUserId(), event.getOrderId(),
                        OrderNotification.TYPE_CANCELLED, "订单已超时取消",
                        "订单 " + event.getOrderNumber() + " 超时未支付，已自动取消。");
            }
        } catch (Exception e) {
            // 这里已经是队列的最后一站：抛出去只会让消息重新回到自己，只会转圈
            log.error("死信消息补做失败，需要人工处理。payload={}", payload, e);
        } finally {
            channel.basicAck(deliveryTag, false);
            MessageTrace.clear();
        }
    }
}
