package com.sky.service;

import com.sky.entity.OrderRefund;
import com.sky.entity.Orders;

/**
 * 退款：申请、回调确认、失败补偿。
 */
public interface OrderRefundService {

    /**
     * 发起退款。可重复调用：退款单号由订单号派生且有唯一索引，
     * 已成功/进行中的记录不会被再退一次。
     */
    void applyRefund(Orders order, String reason);

    /**
     * 处理微信退款回调。
     *
     * @param outRefundNo     商户退款单号
     * @param refundStatus    微信退款状态，SUCCESS 才算成功
     * @param refundAmountFen 回调里的退款金额（分），必须与申请金额一致
     * @param wechatRefundId  微信退款单号
     */
    void handleRefundNotify(String outRefundNo, String refundStatus, Integer refundAmountFen, String wechatRefundId);

    /**
     * 补偿：重试待发起与失败的退款记录。
     *
     * @return 本次处理的记录数
     */
    int retryPending(int limit);

    /** 查询某订单的退款记录，用于对账与前端展示 */
    java.util.List<OrderRefund> listByOrder(Long orderId);
}
