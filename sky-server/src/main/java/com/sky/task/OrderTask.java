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

    /**
     * 判定基准是下单时间（order_time），不是进入派送中的时间：
     * 下单超过这个时长还停在派送中，就按"当天未闭环订单"收尾。
     */
    private static final int STALE_MINUTES = 60;
    /** 单次扫描上限，避免积压时把整表读进内存 */
    private static final int BATCH_LIMIT = 500;

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

        LocalDateTime time = LocalDateTime.now().minusMinutes(STALE_MINUTES);

        List<Orders> ordersList = orderMapper.getByStatusAndOrderTimeLT(
                Orders.DELIVERY_IN_PROGRESS, time, BATCH_LIMIT);

        int completed = 0;
        for (Orders orders : ordersList) {
            // 带前置状态的条件更新：全字段 update 会把用户在这期间改过的字段一起覆盖掉，
            // 而且和"用户点完成/商家派送"并发时会互相盖掉对方的状态
            completed += orderMapper.completeByIdAndExpectedStatus(orders.getId(),
                    Orders.DELIVERY_IN_PROGRESS, Orders.COMPLETED, LocalDateTime.now());
        }
        log.info("派送超时订单处理完成，扫描={}，实际完成={}", ordersList.size(), completed);
    }
}
