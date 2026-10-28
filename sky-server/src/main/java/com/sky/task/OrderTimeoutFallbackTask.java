package com.sky.task;

import com.sky.entity.OrderNotification;
import com.sky.entity.Orders;
import com.sky.lock.RedisLock;
import com.sky.mapper.OrderMapper;
import com.sky.service.OrderNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时订单的扫描兜底。
 * <p>
 * 这个任务**不再和 MQ 互斥**。原来的条件是"MQ 启用时不创建"，等于假设"消息一定会被消费成功"；
 * 但消息可能进死信、可能因为 broker 重启丢失、也可能在发送侧就到不了 —— 一旦如此，
 * 待支付订单就永远挂在那里，而且没有任何人会去看。
 * <p>
 * 保留双轨的代价很低：取消本身是 `where status = 1` 的条件更新，重复扫描同一批订单影响 0 行，
 * 所以 MQ 正常时这个任务只是每分钟多一次很便宜的索引扫描，MQ 异常时它是唯一的出口。
 */
@Component
@Slf4j
public class OrderTimeoutFallbackTask {

    /** 超过多少分钟未支付视为超时，需要与 RabbitMQ 延迟队列的 TTL 一致 */
    private static final int TIMEOUT_MINUTES = 15;
    private static final int BATCH_LIMIT = 500;
    private static final String LOCK_KEY = "order:task:timeout-fallback:lock";

    private final OrderMapper orderMapper;
    private final RedisLock redisLock;
    private final OrderNotificationService notificationService;

    public OrderTimeoutFallbackTask(OrderMapper orderMapper, RedisLock redisLock,
                                    OrderNotificationService notificationService) {
        this.orderMapper = orderMapper;
        this.redisLock = redisLock;
        this.notificationService = notificationService;
    }

    /**
     * 这个任务在 MQ 关闭时兜底，本身是 @Scheduled —— 集群下每个实例都会各自触发一次，
     * 所以必须加分布式锁，否则同一批超时订单会被重复取消、重复推送。
     */
    @Scheduled(cron = "0 * * * * ?") //每分钟触发一次
    public void processTimeoutOrder() {
        if (!redisLock.tryLock(LOCK_KEY, Duration.ZERO)) {
            log.debug("已有实例在执行超时订单兜底任务，本次跳过");
            return;
        }
        try {
            log.info("兜底任务：定时处理超时订单：{}", LocalDateTime.now());

            LocalDateTime time = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);

            List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(
                    Orders.PENDING_PAYMENT, time, BATCH_LIMIT);

            int cancelled = 0;
            int failed = 0;
            for (Orders orders : ordersList) {
                try {
                    // 只取消仍然是"待支付"的订单：刚支付的订单不能被兜底任务判成超时
                    int rows = orderMapper.cancelPendingPaymentById(orders.getId(),
                            "订单超时，自动取消", LocalDateTime.now());
                    cancelled += rows;
                    if (rows == 1) {
                        notificationService.notify(orders.getUserId(), orders.getId(),
                                OrderNotification.TYPE_CANCELLED, "订单已超时取消",
                                "订单 " + orders.getNumber() + " 超过 " + TIMEOUT_MINUTES + " 分钟未支付，已自动取消。");
                    }
                } catch (Exception e) {
                    // 一笔失败不能带走整批：原来异常直接冒到调度线程被吞掉，
                    // 后面的订单这一轮一条都不处理，看起来"任务跑过了"其实没有
                    failed++;
                    log.error("超时订单取消失败，orderId={}", orders.getId(), e);
                }
            }
            if (failed > 0) {
                log.error("超时取消兜底有 {} 笔处理失败，需人工核对，本轮扫描={}", failed, ordersList.size());
            }
            log.info("超时订单兜底完成，扫描={}，实际取消={}", ordersList.size(), cancelled);
        } catch (Exception e) {
            log.error("超时订单兜底任务整体执行失败", e);
        } finally {
            redisLock.unlock(LOCK_KEY);
        }
    }
}
