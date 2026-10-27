package com.sky.messaging;

import com.sky.event.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 订单事件分发：事务提交后触发立即发送本地消息表中的消息。
 * 消息在事务内已写入 mq_message 表，这里只是"尽早发送"；真正兜底的是定时补偿任务，
 * 因此即使这里发送失败，消息也不会丢。
 */
@Component
@Slf4j
public class OrderEventDispatcher {

    private final MqMessageSender mqMessageSender;

    public OrderEventDispatcher(MqMessageSender mqMessageSender) {
        this.mqMessageSender = mqMessageSender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(OrderCreatedEvent event) {
        log.info("订单事务已提交，触发立即发送，orderId={}", event.getOrderId());
        mqMessageSender.sendPending();
    }
}
