package com.sky.messaging;

import com.sky.event.OrderCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.core.task.TaskExecutor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 事务提交后的发送必须异步，且失败不能反过来影响调用方。
 * <p>
 * 同步发送时 HTTP 线程要站在已提交的事务外面等 broker 确认，MQ 一慢下单接口就跟着慢；
 * 线程池饱和被拒绝也只是"这条消息等下一个补偿周期"，不是下单失败。
 */
@ExtendWith(MockitoExtension.class)
class OrderEventDispatcherTest {

    @Mock
    private MqMessageSender mqMessageSender;
    @Mock
    private TaskExecutor mqSendExecutor;

    @Test
    @DisplayName("提交后只是把发送任务丢进线程池，不在当前线程等确认")
    void submitsSendTask() {
        OrderEventDispatcher dispatcher = new OrderEventDispatcher(mqMessageSender, mqSendExecutor);

        dispatcher.dispatch(event());

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mqSendExecutor).execute(task.capture());
        task.getValue().run();
        verify(mqMessageSender).sendPending();
    }

    @Test
    @DisplayName("线程池饱和被拒绝：只记日志，异常不会冒到下单链路")
    void swallowsRejection() {
        OrderEventDispatcher dispatcher = new OrderEventDispatcher(mqMessageSender, mqSendExecutor);
        doThrow(new TaskRejectedException("pool saturated")).when(mqSendExecutor).execute(org.mockito.ArgumentMatchers.any());

        assertThatCode(() -> dispatcher.dispatch(event())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("异步线程里的发送失败被接住：补偿任务会重投，但日志必须留下")
    void swallowsFailureInsideTask() {
        OrderEventDispatcher dispatcher = new OrderEventDispatcher(mqMessageSender, mqSendExecutor);
        doThrow(new IllegalStateException("broker down")).when(mqMessageSender).sendPending();

        dispatcher.dispatch(event());

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mqSendExecutor).execute(task.capture());
        assertThatCode(task.getValue()::run).doesNotThrowAnyException();
    }

    private static OrderCreatedEvent event() {
        return new OrderCreatedEvent(1L, "202610280001", 9L,
                new BigDecimal("36.00"), LocalDateTime.of(2026, 10, 28, 12, 0));
    }
}
