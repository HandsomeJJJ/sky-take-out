package com.sky.service;

import com.sky.entity.OrderNotification;
import com.sky.entity.OrderRefund;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.OrderRefundMapper;
import com.sky.service.impl.OrderRefundServiceImpl;
import com.sky.utils.WeChatPayUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款闭环：单号幂等、结果落状态、失败进补偿、回调对金额。
 */
@ExtendWith(MockitoExtension.class)
class OrderRefundServiceImplTest {

    private static final String REFUND_NO = "RF202610280001";

    @Mock
    private OrderRefundMapper refundMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private WeChatPayUtil weChatPayUtil;
    @Mock
    private OrderNotificationService notificationService;

    @InjectMocks
    private OrderRefundServiceImpl refundService;

    private Orders order() {
        Orders orders = new Orders();
        orders.setId(5L);
        orders.setUserId(100L);
        orders.setNumber("202610280001");
        orders.setAmount(new BigDecimal("50.00"));
        orders.setStatus(Orders.CANCELLED);
        orders.setPayStatus(Orders.PAID);
        return orders;
    }

    private OrderRefund record(int status, int retried) {
        return OrderRefund.builder()
                .id(1L)
                .orderId(5L)
                .orderNumber("202610280001")
                .refundNo(REFUND_NO)
                .amount(new BigDecimal("50.00"))
                .status(status)
                .retryCount(retried)
                .build();
    }

    @Test
    @DisplayName("退款单号由订单号派生并先落库，微信受理后按结果写状态")
    void applyRefundPersistsRecordBeforeCallingWeChat() throws Exception {
        when(refundMapper.getByRefundNo(REFUND_NO))
                .thenReturn(record(OrderRefund.PENDING, 0))
                .thenReturn(record(OrderRefund.PENDING, 0));
        // markResult 影响 1 行才算这次真正从"未成功"推进到"成功"，重复回调会拿到 0
        when(refundMapper.markResult(eq(REFUND_NO), eq(OrderRefund.SUCCESS), eq("50300"), isNull(), any()))
                .thenReturn(1);
        when(weChatPayUtil.refund("202610280001", REFUND_NO, new BigDecimal("50.00"), new BigDecimal("50.00")))
                .thenReturn("{\"refund_id\":\"50300\",\"status\":\"SUCCESS\"}");
        when(orderMapper.getById(5L)).thenReturn(order());

        refundService.applyRefund(order(), "订单取消退款");

        ArgumentCaptor<OrderRefund> captor = ArgumentCaptor.forClass(OrderRefund.class);
        verify(refundMapper).insertIfAbsent(captor.capture());
        OrderRefund inserted = captor.getValue();
        assertThat(inserted.getRefundNo()).isEqualTo(REFUND_NO);
        assertThat(inserted.getStatus()).isEqualTo(OrderRefund.PENDING);
        assertThat(inserted.getAmount()).isEqualByComparingTo("50.00");

        verify(refundMapper).markResult(eq(REFUND_NO), eq(OrderRefund.SUCCESS), eq("50300"), isNull(), any());
        verify(orderMapper).markRefunded(5L);
        verify(notificationService).notifyOrder(any(), eq(OrderNotification.TYPE_REFUND), anyString(), anyString());
    }

    @Test
    @DisplayName("已经退成功过的订单不再调微信：重复申请不会造成二次退款")
    void applyRefundSkipsWhenAlreadySucceeded() throws Exception {
        when(refundMapper.getByRefundNo(REFUND_NO)).thenReturn(record(OrderRefund.SUCCESS, 0));

        refundService.applyRefund(order(), "重复申请");

        verify(weChatPayUtil, never()).refund(anyString(), anyString(), any(), any());
        verify(refundMapper, never()).markResult(anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("微信返回业务失败时不吞掉：置为失败并安排退避重试")
    void applyRefundSchedulesRetryOnWeChatError() throws Exception {
        when(refundMapper.getByRefundNo(REFUND_NO)).thenReturn(record(OrderRefund.PENDING, 0));
        when(weChatPayUtil.refund(anyString(), anyString(), any(), any()))
                .thenReturn("{\"code\":\"SYSTEMERROR\",\"message\":\"系统错误\"}");

        refundService.applyRefund(order(), "订单取消退款");

        ArgumentCaptor<LocalDateTime> next = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(refundMapper).markRetry(eq(REFUND_NO), eq(OrderRefund.FAILED), next.capture(),
                anyString(), any(LocalDateTime.class));
        assertThat(next.getValue()).isAfter(LocalDateTime.now().minusSeconds(5));
    }

    @Test
    @DisplayName("退款调用抛异常同样进入补偿，而不是只留一行日志")
    void applyRefundSchedulesRetryOnException() throws Exception {
        when(refundMapper.getByRefundNo(REFUND_NO)).thenReturn(record(OrderRefund.PENDING, 0));
        when(weChatPayUtil.refund(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("连接超时"));

        refundService.applyRefund(order(), "订单取消退款");

        verify(refundMapper).markRetry(eq(REFUND_NO), eq(OrderRefund.FAILED), any(), anyString(), any());
    }

    @Test
    @DisplayName("重试次数达到上限后置为需人工处理，不再无限占用补偿窗口")
    void applyRefundMarksExhaustedAfterMaxRetries() throws Exception {
        when(refundMapper.getByRefundNo(REFUND_NO)).thenReturn(record(OrderRefund.FAILED, 7));
        when(weChatPayUtil.refund(anyString(), anyString(), any(), any()))
                .thenReturn("{\"code\":\"RULE_LIMIT\",\"message\":\"超出退款期限\"}");

        refundService.applyRefund(order(), "补偿重试");

        verify(refundMapper).markRetry(eq(REFUND_NO), eq(OrderRefund.EXHAUSTED), any(), anyString(), any());
    }

    @Test
    @DisplayName("退款回调金额与申请不一致时拒绝确认，让微信重试")
    void refundNotifyRejectsAmountMismatch() {
        when(refundMapper.getByRefundNo(REFUND_NO)).thenReturn(record(OrderRefund.PROCESSING, 0));

        assertThatThrownBy(() -> refundService.handleRefundNotify(REFUND_NO, "SUCCESS", 1, "50300"))
                .isInstanceOf(IllegalStateException.class);

        verify(refundMapper, never()).markResult(anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("退款回调金额一致才标记到账，并推进订单支付状态")
    void refundNotifyConfirmsSuccess() {
        when(refundMapper.getByRefundNo(REFUND_NO)).thenReturn(record(OrderRefund.PROCESSING, 0));
        when(refundMapper.markResult(eq(REFUND_NO), eq(OrderRefund.SUCCESS), eq("50300"), isNull(), any()))
                .thenReturn(1);
        when(orderMapper.getById(5L)).thenReturn(order());

        refundService.handleRefundNotify(REFUND_NO, "SUCCESS", 5000, "50300");

        verify(refundMapper).markResult(eq(REFUND_NO), eq(OrderRefund.SUCCESS), eq("50300"), isNull(), any());
        verify(orderMapper).markRefunded(5L);
        verify(notificationService).notifyOrder(any(), eq(OrderNotification.TYPE_REFUND), anyString(), anyString());
    }

    @Test
    @DisplayName("重复的退款回调不会二次推进订单、也不会再发一遍已到账通知")
    void duplicateRefundNotifyDoesNotReconfirm() {
        when(refundMapper.getByRefundNo(REFUND_NO)).thenReturn(record(OrderRefund.SUCCESS, 0));
        when(refundMapper.markResult(anyString(), any(), any(), any(), any())).thenReturn(0);

        refundService.handleRefundNotify(REFUND_NO, "SUCCESS", 5000, "50300");

        verify(orderMapper, never()).markRefunded(anyLong());
        verify(notificationService, never()).notifyOrder(any(), anyInt(), anyString(), anyString());
    }

    @Test
    @DisplayName("未知退款单的回调只告警，不假装处理成功也不让微信无限重投")
    void refundNotifyIgnoresUnknownRefund() {
        when(refundMapper.getByRefundNo("RF-not-exist")).thenReturn(null);

        refundService.handleRefundNotify("RF-not-exist", "SUCCESS", 5000, "50300");

        verify(refundMapper, never()).markResult(anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("补偿任务重投待发起与失败的记录")
    void retryPendingReissuesRecords() throws Exception {
        when(refundMapper.findRetryable(any(), eq(50)))
                .thenReturn(java.util.List.of(record(OrderRefund.FAILED, 1)));
        when(weChatPayUtil.refund(anyString(), anyString(), any(), any()))
                .thenReturn("{\"refund_id\":\"50301\",\"status\":\"PROCESSING\"}");

        int handled = refundService.retryPending(50);

        assertThat(handled).isEqualTo(1);
        verify(refundMapper).markResult(eq(REFUND_NO), eq(OrderRefund.PROCESSING), eq("50301"), isNull(), any());
    }

    @Test
    @DisplayName("金额为 0 的订单不发退款请求")
    void applyRefundSkipsZeroAmount() throws Exception {
        Orders free = order();
        free.setAmount(BigDecimal.ZERO);

        refundService.applyRefund(free, "0 元单");

        verify(weChatPayUtil, never()).refund(anyString(), anyString(), any(), any());
        verify(refundMapper, never()).insertIfAbsent(any());
    }
}
