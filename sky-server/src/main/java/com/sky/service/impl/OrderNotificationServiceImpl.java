package com.sky.service.impl;

import com.sky.context.BaseContext;
import com.sky.entity.OrderNotification;
import com.sky.entity.Orders;
import com.sky.mapper.OrderNotificationMapper;
import com.sky.service.OrderNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class OrderNotificationServiceImpl implements OrderNotificationService {

    /** 单次最多拉取的通知条数，避免 limit 被传成一个大数把整表读进内存 */
    private static final int MAX_LIMIT = 50;

    private final OrderNotificationMapper notificationMapper;

    public OrderNotificationServiceImpl(OrderNotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    /**
     * 状态变更发生在管理端或回调线程，没有登录态，userId 只能从订单本身取。
     */
    public void notifyOrder(Orders order, int type, String title, String content) {
        if (order == null) {
            return;
        }
        notify(order.getUserId(), order.getId(), type, title, content);
    }

    public void notify(Long userId, Long orderId, int type, String title, String content) {
        if (userId == null) {
            log.warn("通知缺少接收人，跳过，orderId={}, type={}", orderId, type);
            return;
        }
        try {
            notificationMapper.insert(OrderNotification.builder()
                    .userId(userId)
                    .orderId(orderId)
                    .type(type)
                    .title(title)
                    .content(content)
                    .createTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            // 业务动作（改状态/退款）此时已经落库，不能因为一条通知失败而对外报错或回滚
            log.error("订单通知写入失败，orderId={}, userId={}, type={}", orderId, userId, type, e);
        }
    }

    public List<OrderNotification> listMine(int limit) {
        int size = limit < 1 ? 20 : Math.min(limit, MAX_LIMIT);
        return notificationMapper.listByUserId(BaseContext.getCurrentId(), size);
    }

    public int unreadCount() {
        return notificationMapper.countUnread(BaseContext.getCurrentId());
    }

    public void markAllRead() {
        notificationMapper.markAllRead(BaseContext.getCurrentId());
    }
}
