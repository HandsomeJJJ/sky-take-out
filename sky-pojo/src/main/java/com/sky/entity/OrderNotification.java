package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户端订单通知。
 * <p>
 * 管理端接单/拒单/派送/完成这些动作改变了用户关心的事实，原来只有支付和催单走了
 * WebSocket 广播，其余动作用户只能靠刷新订单列表发现。WebSocket 在小程序侧并不稳定，
 * 且连接（sid）与用户没有绑定，无法定向推送；因此状态变更统一落一行站内通知，
 * 用户端拉取并按需标记已读。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderNotification implements Serializable {

    public static final int TYPE_PAID = 1;
    public static final int TYPE_CONFIRMED = 2;
    public static final int TYPE_REJECTED = 3;
    public static final int TYPE_DELIVERING = 4;
    public static final int TYPE_COMPLETED = 5;
    public static final int TYPE_CANCELLED = 6;
    public static final int TYPE_REFUND = 7;

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long userId;

    private Long orderId;

    /** 通知类型，见上面的常量 */
    private Integer type;

    private String title;

    private String content;

    /** 0 未读 1 已读 */
    private Integer readFlag;

    private LocalDateTime createTime;
}
