package com.sky.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.sky.entity.OrderRefund;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.OrderRefundMapper;
import com.sky.service.OrderNotificationService;
import com.sky.service.OrderRefundService;
import com.sky.utils.JsonUtils;
import com.sky.utils.WeChatPayUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class OrderRefundServiceImpl implements OrderRefundService {

    /** 补偿重试上限，超过后置为"需人工处理"，避免对一笔微信侧持续失败的退款无限重试 */
    private static final int MAX_RETRY = 8;

    /** 单次补偿最多处理多少条 */
    private static final int DEFAULT_BATCH = 50;

    /** 微信退款状态 */
    private static final String REFUND_SUCCESS = "SUCCESS";
    private static final String REFUND_PROCESSING = "PROCESSING";

    private final OrderRefundMapper refundMapper;
    private final OrderMapper orderMapper;
    private final WeChatPayUtil weChatPayUtil;
    private final OrderNotificationService notificationService;

    public OrderRefundServiceImpl(OrderRefundMapper refundMapper,
                                  OrderMapper orderMapper,
                                  WeChatPayUtil weChatPayUtil,
                                  OrderNotificationService notificationService) {
        this.refundMapper = refundMapper;
        this.orderMapper = orderMapper;
        this.weChatPayUtil = weChatPayUtil;
        this.notificationService = notificationService;
    }

    /**
     * 这里刻意不加 @Transactional：申请退款是一次外部 HTTP 调用，
     * 把它包在事务里会把数据库连接按住几百毫秒到几秒，微信慢的时候连接池会被拖空。
     * 记录先落库（幂等 upsert），调用结果再单独更新，任何一步崩溃都能被补偿任务捡起来。
     */
    public void applyRefund(Orders order, String reason) {
        if (order == null || order.getNumber() == null) {
            log.warn("订单信息不完整，无法发起退款");
            return;
        }
        BigDecimal amount = order.getAmount();
        if (amount == null || amount.signum() <= 0) {
            log.warn("订单金额为 0，无需退款，orderNumber={}", order.getNumber());
            return;
        }

        String refundNo = refundNoOf(order.getNumber());
        LocalDateTime now = LocalDateTime.now();

        // 唯一键 + on duplicate do nothing：并发重复申请只会留下一行，且状态不会被倒回
        refundMapper.insertIfAbsent(OrderRefund.builder()
                .orderId(order.getId())
                .orderNumber(order.getNumber())
                .refundNo(refundNo)
                .amount(amount)
                .status(OrderRefund.PENDING)
                .nextRetryTime(now)
                .reason(reason)
                .createTime(now)
                .updateTime(now)
                .build());

        OrderRefund record = refundMapper.getByRefundNo(refundNo);
        if (record == null) {
            throw new IllegalStateException("退款记录写入后读不到，refundNo=" + refundNo);
        }
        if (record.getStatus() != null
                && (record.getStatus() == OrderRefund.PROCESSING
                || record.getStatus() == OrderRefund.SUCCESS
                || record.getStatus() == OrderRefund.EXHAUSTED)) {
            log.info("退款已在处理或已完成，跳过重复申请。refundNo={}, status={}", refundNo, record.getStatus());
            return;
        }

        callWeChat(order.getNumber(), refundNo, amount);
    }

    /**
     * 调微信申请退款并按结果落状态。
     */
    private void callWeChat(String orderNumber, String refundNo, BigDecimal amount) {
        try {
            String response = weChatPayUtil.refund(orderNumber, refundNo, amount, amount);
            JsonNode json = JsonUtils.readTree(response);
            String status = json.path("status").asText(null);
            String wechatRefundId = json.path("refund_id").asText(null);

            if (REFUND_SUCCESS.equals(status)) {
                markSuccess(refundNo, wechatRefundId, amount);
                log.info("退款成功，refundNo={}, refundId={}", refundNo, wechatRefundId);
                return;
            }
            if (REFUND_PROCESSING.equals(status)) {
                // 已受理，最终结果以退款回调为准
                refundMapper.markResult(refundNo, OrderRefund.PROCESSING, wechatRefundId, null, LocalDateTime.now());
                log.info("退款已受理，等待微信回调确认，refundNo={}", refundNo);
                return;
            }
            // 微信返回了业务错误（或退款异常/关闭状态），按失败补偿
            scheduleRetry(refundNo, "微信退款未成功：" + truncate(response));
        } catch (Exception e) {
            scheduleRetry(refundNo, "退款调用异常：" + truncate(e.toString()));
            log.error("调用微信退款失败，已进入补偿队列，refundNo={}", refundNo, e);
        }
    }

    private void markSuccess(String refundNo, String wechatRefundId, BigDecimal amount) {
        int rows = refundMapper.markResult(refundNo, OrderRefund.SUCCESS, wechatRefundId, null, LocalDateTime.now());
        if (rows != 1) {
            // 已经是成功/需人工：重复回调不再二次推进订单，也不再给用户发第二遍"已到账"
            log.info("退款成功状态未再次更新（可能重复回调），refundNo={}", refundNo);
            return;
        }
        // 订单支付状态推进到"已退款"：只允许从"已支付"改，重复回调打不到已退款的单
        OrderRefund record = refundMapper.getByRefundNo(refundNo);
        if (record != null && record.getOrderId() != null) {
            orderMapper.markRefunded(record.getOrderId());
            Orders order = orderMapper.getById(record.getOrderId());
            if (order != null) {
                notificationService.notifyOrder(order, com.sky.entity.OrderNotification.TYPE_REFUND,
                        "退款已到账", "订单 " + order.getNumber() + " 退款 " + amount + " 元已到账。");
            }
        }
    }

    private void scheduleRetry(String refundNo, String error) {
        OrderRefund record = refundMapper.getByRefundNo(refundNo);
        int retried = record == null || record.getRetryCount() == null ? 0 : record.getRetryCount();
        boolean exhausted = retried + 1 >= MAX_RETRY;
        LocalDateTime next = exhausted ? null : LocalDateTime.now().plusMinutes(backoffMinutes(retried));
        refundMapper.markRetry(refundNo,
                exhausted ? OrderRefund.EXHAUSTED : OrderRefund.FAILED,
                next == null ? LocalDateTime.now() : next,
                truncate(error), LocalDateTime.now());
        if (exhausted) {
            log.error("退款重试已达上限 {} 次，需要人工处理，refundNo={}，最后错误={}", MAX_RETRY, refundNo, error);
        }
    }

    /** 指数退避：1、2、4、8、16、32、64、120 分钟 */
    private static long backoffMinutes(int retried) {
        long minutes = 1L << Math.min(retried, 7);
        return Math.min(minutes, 120);
    }

    public void handleRefundNotify(String outRefundNo, String refundStatus, Integer refundAmountFen, String wechatRefundId) {
        OrderRefund record = refundMapper.getByRefundNo(outRefundNo);
        if (record == null) {
            // 重投也不会变出来，只能靠日志/告警人工对账，不能伪装成处理成功之外的失败让微信无限重试
            log.error("收到未知退款单的回调，需要人工核对。refundNo={}, refundStatus={}", outRefundNo, refundStatus);
            return;
        }
        if (!REFUND_SUCCESS.equals(refundStatus)) {
            log.warn("退款回调状态非成功，不做处理。refundNo={}, status={}", outRefundNo, refundStatus);
            return;
        }

        int expectedFen = WeChatPayUtil.toFen(record.getAmount());
        if (refundAmountFen == null || refundAmountFen != expectedFen) {
            log.error("退款回调金额与申请金额不一致，拒绝确认。refundNo={}, 回调={}分, 申请={}分",
                    outRefundNo, refundAmountFen, expectedFen);
            // 抛错让回调返回 FAIL，微信会重试；金额对不上绝不能先告诉用户"已到账"
            throw new IllegalStateException("退款金额与申请不一致");
        }

        markSuccess(outRefundNo, wechatRefundId, record.getAmount());
        log.info("退款回调确认成功，refundNo={}", outRefundNo);
    }

    public int retryPending(int limit) {
        int batch = limit < 1 ? DEFAULT_BATCH : limit;
        LocalDateTime now = LocalDateTime.now();
        List<OrderRefund> pending = refundMapper.findRetryable(now, batch);
        for (OrderRefund record : pending) {
            if (record.getAmount() == null || record.getAmount().signum() <= 0) {
                refundMapper.markResult(record.getRefundNo(), OrderRefund.EXHAUSTED, null, "金额非法", now);
                continue;
            }
            log.info("补偿重试退款，refundNo={}, 已重试次数={}", record.getRefundNo(), record.getRetryCount());
            callWeChat(record.getOrderNumber(), record.getRefundNo(), record.getAmount());
        }
        return pending.size();
    }

    public List<OrderRefund> listByOrder(Long orderId) {
        return refundMapper.getByOrderId(orderId);
    }

    /**
     * 退款单号由订单号派生：同一笔订单永远只对应一个退款单号，
     * 这也是重复点击/重复消费/补偿重投都不会造成二次退款的根因。
     */
    private static String refundNoOf(String orderNumber) {
        return "RF" + orderNumber;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
