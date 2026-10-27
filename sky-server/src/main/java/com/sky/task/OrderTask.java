package com.sky.task;

import com.sky.entity.Orders;
import com.sky.lock.RedisLock;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务类，定时处理订单状态。
 *
 * 超时取消已由 RabbitMQ 延迟队列接管（见 RabbitOrderTimeoutConsumer），
 * 这里只保留 MQ 不可用时的兜底任务（OrderTimeoutFallbackTask）。
 */
@Component
@Slf4j
public class OrderTask {

    private final OrderMapper orderMapper;
    private final RedisLock redisLock;

    public OrderTask(OrderMapper orderMapper, RedisLock redisLock) {
        this.orderMapper = orderMapper;
        this.redisLock = redisLock;
    }

    /**
     * 处理一直处于派送中状态的订单。
     * @Scheduled 是进程内调度，集群下多实例会同时触发，必须加分布式锁保证只执行一次。
     */
    @Scheduled(cron = "0 0 1 * * ?") //每天凌晨1点触发一次
    public void processDeliveryOrder() {
        String lockKey = "order:task:delivery:lock";
        if (!redisLock.tryLock(lockKey, Duration.ofSeconds(1))) {
            log.info("已有实例在执行派送订单处理，本次跳过");
            return;
        }
        try {
            doProcess();
        } finally {
            redisLock.unlock(lockKey);
        }
    }

    private void doProcess() {
        log.info("定时处理处于派送中的订单：{}", LocalDateTime.now());

        LocalDateTime time = LocalDateTime.now().plusMinutes(-60);

        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(Orders.DELIVERY_IN_PROGRESS, time);

        if (ordersList != null && ordersList.size() > 0) {
            for (Orders orders : ordersList) {
                orders.setStatus(Orders.COMPLETED);
                orderMapper.update(orders);
            }
        }
    }
}
