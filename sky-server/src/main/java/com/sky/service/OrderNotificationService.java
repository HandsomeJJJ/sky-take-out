package com.sky.service;

import com.sky.entity.OrderNotification;
import com.sky.entity.Orders;

import java.util.List;

/**
 * 用户端订单通知。
 */
public interface OrderNotificationService {

    /**
     * 记录一条订单状态变更通知。best-effort：通知写失败不能把已经完成的业务动作回滚或报错，
     * 但必须留下 error 日志，因为对用户来说这就是"我的单被人动了却没人告诉我"。
     */
    void notifyOrder(Orders order, int type, String title, String content);

    /**
     * 同上，但调用方手上没有完整订单对象（MQ 消费者、定时任务只带 id/userId）。
     */
    void notify(Long userId, Long orderId, int type, String title, String content);

    List<OrderNotification> listMine(int limit);

    int unreadCount();

    void markAllRead();
}
