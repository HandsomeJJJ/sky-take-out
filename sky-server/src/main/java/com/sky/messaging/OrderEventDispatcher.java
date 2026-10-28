package com.sky.messaging;

import com.sky.config.AsyncExecutorConfiguration;
import com.sky.event.OrderCreatedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 订单事件分发：事务提交后**异步**触发立即发送本地消息表中的消息。
 * <p>
 * 原来这段是在 AFTER_COMMIT 里同步跑的，而发送要等 broker 确认（Rabbit 的
 * {@code waitForConfirms} 最长 5 秒、Kafka 的 {@code future.get} 同样阻塞）——
 * 也就是说用户点"下单"之后，HTTP 线程要站在已经提交的事务外面等 MQ，
 * MQ 一慢，下单接口就跟着慢，虽然订单本身已经成功了。
 * <p>
 * 改成异步不会丢消息：消息本体是在业务事务里写进 mq_message 的，
 * 这里只是"尽早发一次"，没发出去或线程池拒绝，都由 {@code MqMessageCompensator} 重投。
 */
@Component
@Slf4j
public class OrderEventDispatcher {

    private final MqMessageSender mqMessageSender;
    private final TaskExecutor mqSendExecutor;

    public OrderEventDispatcher(MqMessageSender mqMessageSender,
                                @Qualifier(AsyncExecutorConfiguration.MQ_SEND_EXECUTOR) TaskExecutor mqSendExecutor) {
        this.mqMessageSender = mqMessageSender;
        this.mqSendExecutor = mqSendExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(OrderCreatedEvent event) {
        try {
            mqSendExecutor.execute(() -> {
                try {
                    mqMessageSender.sendPending();
                } catch (Exception e) {
                    // 异步线程里的异常没人接，不吞掉就会静默消失；补偿任务会重投，但日志必须留下
                    log.error("订单提交后的异步发送失败，等待补偿任务重投，orderId={}", event.getOrderId(), e);
                }
            });
        } catch (Exception e) {
            // 线程池饱和被拒绝：不影响正确性，只是这条消息要等下一个补偿周期（30 秒内）
            log.warn("异步发送队列已满，本次不立即发送，交给补偿任务，orderId={}", event.getOrderId());
        }
    }
}
