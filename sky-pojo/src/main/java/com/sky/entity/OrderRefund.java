package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款申请记录。
 * <p>
 * 订单表的 pay_status 只能表达"这笔订单退过款"，表达不了"退款申请发到微信之后有没有成功"。
 * 退款是异步的（申请 -> 微信受理 -> 回调确认），中间态必须有一行可重试、可对账的记录，
 * 否则退款调用失败就只剩一行日志，钱实际没退却没人知道。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderRefund implements Serializable {

    /** 待发起：记录已建，还没成功调到微信 */
    public static final int PENDING = 0;
    /** 微信已受理，等回调确认 */
    public static final int PROCESSING = 1;
    /** 退款成功 */
    public static final int SUCCESS = 2;
    /** 本次调用失败，等补偿重试 */
    public static final int FAILED = 3;
    /** 重试次数用尽，需人工处理 */
    public static final int EXHAUSTED = 4;

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long orderId;

    /** 商户订单号 */
    private String orderNumber;

    /** 商户退款单号，由订单号派生并建唯一索引，是"同一笔订单不会退两次"的落点 */
    private String refundNo;

    /** 退款金额（元） */
    private BigDecimal amount;

    private Integer status;

    private Integer retryCount;

    /** 下次重试时间；退避递增，避免微信侧异常时无脑高频重试 */
    private LocalDateTime nextRetryTime;

    private String wechatRefundId;

    private String lastError;

    private String reason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
