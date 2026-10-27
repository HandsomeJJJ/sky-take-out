package com.sky.task;

import com.sky.entity.Orders;
import com.sky.lock.RedisLock;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时订单的兜底处理：仅在 RabbitMQ 延迟队列未启用时生效。
 * 主链路是消息中间件，这里是降级方案，避免 MQ 整体不可用时订单永远挂在待支付状态。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "sky.messaging.rabbitmq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class OrderTimeoutFallbackTask {

    /** 超过多少分钟未支付视为超时，需要与 RabbitMQ 延迟队列的 TTL 一致 */
    private static final int TIMEOUT_MINUTES = 15;
    private static final int BATCH_LIMIT = 500;
    private static final String LOCK_KEY = "order:task:timeout-fallback:lock";

    private final OrderMapper orderMapper;
    private final RedisLock redisLock;

    public OrderTimeoutFallbackTask(OrderMapper orderMapper, RedisLock redisLock) {
        this.orderMapper = orderMapper;
        this.redisLock = redisLock;
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
            for (Orders orders : ordersList) {
                // 只取消仍然是"待支付"的订单：刚支付的订单不能被兜底任务判成超时
                cancelled += orderMapper.cancelPendingPaymentById(orders.getId(),
                        "订单超时，自动取消", LocalDateTime.now());
            }
            log.info("超时订单兜底完成，扫描={}，实际取消={}", ordersList.size(), cancelled);
        } finally {
            redisLock.unlock(LOCK_KEY);
        }
    }
}
