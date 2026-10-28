package com.sky.task;

import com.sky.entity.OrderNotification;
import com.sky.entity.Orders;
import com.sky.lock.RedisLock;
import com.sky.mapper.OrderMapper;
import com.sky.properties.OrderProperties;
import com.sky.service.OrderNotificationService;
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
 * 这里只保留派送超时收尾；超时取消另有 OrderTimeoutFallbackTask 做对账兜底。
 */
@Component
@Slf4j
public class OrderTask {

    /** 单次扫描上限，避免积压时把整表读进内存 */
    private static final int BATCH_LIMIT = 500;

    private final OrderMapper orderMapper;
    private final RedisLock redisLock;
    private final OrderNotificationService notificationService;
    private final OrderProperties orderProperties;

    public OrderTask(OrderMapper orderMapper, RedisLock redisLock,
                     OrderNotificationService notificationService, OrderProperties orderProperties) {
        this.orderMapper = orderMapper;
        this.redisLock = redisLock;
        this.notificationService = notificationService;
        this.orderProperties = orderProperties;
    }

    /**
     * 处理一直处于派送中状态的订单（自动确认收货）。
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
        } catch (Exception e) {
            // 调度线程里的异常必须自己接住：冒出去这一轮就静默失败，而且没有任何人会被通知
            log.error("派送超时收尾任务执行失败", e);
        } finally {
            redisLock.unlock(lockKey);
        }
    }

    private void doProcess() {
        int staleMinutes = orderProperties.getAutoCompleteAfterMinutes();
        log.info("定时处理派送超过 {} 分钟的订单：{}", staleMinutes, LocalDateTime.now());

        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(staleMinutes);

        // 判定基准是"开始派送时间"，不是下单时间：
        // 用下单时间的话，接单+备餐占掉的时间会压缩派送窗口，用户还没收到餐，订单就已经"已完成"并计入营业额
        List<Orders> ordersList = orderMapper.getStaleByDeliveryStartTime(
                Orders.DELIVERY_IN_PROGRESS, staleBefore, BATCH_LIMIT);

        int completed = 0;
        int failed = 0;
        for (Orders orders : ordersList) {
            try {
                // 带前置状态的条件更新：全字段 update 会把用户在这期间改过的字段一起覆盖掉，
                // 而且和"用户点完成/商家派送"并发时会互相盖掉对方的状态
                int rows = orderMapper.completeByIdAndExpectedStatus(orders.getId(),
                        Orders.DELIVERY_IN_PROGRESS, Orders.COMPLETED, LocalDateTime.now());
                completed += rows;
                if (rows == 1) {
                    notificationService.notify(orders.getUserId(), orders.getId(),
                            OrderNotification.TYPE_COMPLETED, "订单已自动完成",
                            "订单 " + orders.getNumber() + " 派送超过 " + staleMinutes
                                    + " 分钟，已自动确认收货。");
                }
            } catch (Exception e) {
                // 单个订单失败不能带走整批：原来一条抛异常，剩下的订单这一轮全部不处理，
                // 而异常本身被调度器吞掉，看起来"任务跑过了"
                failed++;
                log.error("派送超时订单收尾失败，orderId={}", orders.getId(), e);
            }
        }
        if (failed > 0) {
            log.error("派送超时收尾有 {} 个订单处理失败，需人工核对，本轮扫描={}", failed, ordersList.size());
        }
        log.info("派送超时订单处理完成，扫描={}，实际完成={}", ordersList.size(), completed);
    }
}
