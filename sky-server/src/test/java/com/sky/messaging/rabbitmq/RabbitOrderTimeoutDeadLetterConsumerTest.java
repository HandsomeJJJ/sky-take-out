package com.sky.messaging.rabbitmq;

import com.rabbitmq.client.Channel;
import com.sky.entity.OrderNotification;
import com.sky.event.OrderCreatedEvent;
import com.sky.mapper.OrderMapper;
import com.sky.service.OrderNotificationService;
import com.sky.utils.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 死信消费者。
 * <p>
 * 三件事都要锁住：进死信不等于订单可以一直待支付（必须补做取消）；
 * 无论补做成败都要 ack（配置是 manual ack，不 ack 会让这条消息在通道关闭后重新入队、无限转圈）；
 * 报文坏掉时不能把异常抛回容器。
 */
@ExtendWith(MockitoExtension.class)
class RabbitOrderTimeoutDeadLetterConsumerTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderNotificationService notificationService;
    @Mock
    private Channel channel;

    private RabbitOrderTimeoutDeadLetterConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new RabbitOrderTimeoutDeadLetterConsumer(orderMapper, notificationService);
    }

    @Test
    @DisplayName("补做超时取消并通知用户，然后 ack")
    void repairsCancellationAndAcks() throws IOException {
        when(orderMapper.cancelPendingPaymentById(eq(1L), anyString(), any())).thenReturn(1);

        consumer.onDeadLetter(payload(1L, "202610280001"), channel, 11L, null);

        verify(notificationService).notify(eq(9L), eq(1L), eq(OrderNotification.TYPE_CANCELLED),
                anyString(), anyString());
        verify(channel).basicAck(11L, false);
    }

    @Test
    @DisplayName("主消费者已经取消过：条件更新影响 0 行，不重复通知，但仍然 ack")
    void skipsAlreadyCancelledOrder() throws IOException {
        when(orderMapper.cancelPendingPaymentById(anyLong(), anyString(), any())).thenReturn(0);

        consumer.onDeadLetter(payload(2L, "202610280002"), channel, 12L, null);

        verify(notificationService, never()).notify(any(), any(), anyInt(), any(), any());
        verify(channel).basicAck(12L, false);
    }

    @Test
    @DisplayName("报文无法解析：留下痕迹后 ack，不再抛异常造成重回队列")
    void acksUnparsablePayload() throws IOException {
        assertThatCode(() -> consumer.onDeadLetter("{ not json", channel, 13L, null))
                .doesNotThrowAnyException();

        verify(channel).basicAck(13L, false);
        verify(orderMapper, never()).cancelPendingPaymentById(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("补做本身失败也照样 ack：死信已经是最后一站，剩下的交给人工/告警")
    void acksEvenWhenRepairFails() throws IOException {
        when(orderMapper.cancelPendingPaymentById(anyLong(), anyString(), any()))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> consumer.onDeadLetter(payload(3L, "202610280003"), channel, 14L, null))
                .doesNotThrowAnyException();

        verify(channel).basicAck(14L, false);
    }

    private static String payload(Long orderId, String number) {
        return JsonUtils.toJson(new OrderCreatedEvent(orderId, number, 9L,
                new BigDecimal("36.00"), LocalDateTime.of(2026, 10, 28, 12, 0)));
    }
}
