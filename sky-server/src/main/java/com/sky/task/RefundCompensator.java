package com.sky.task;

import com.sky.lock.RedisLock;
import com.sky.service.OrderRefundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 退款补偿任务：把"退款申请已登记但没成功"的记录重新推一遍。
 * <p>
 * 退款失败的兜底不能只是一行 error 日志——钱没退给用户是资损方向的差错，
 * 必须有个持续重试 + 达上限转人工的闭环。
 */
@Component
@Slf4j
public class RefundCompensator {

    private static final String LOCK_KEY = "refund:compensate:lock";
    private static final int BATCH = 50;

    private final OrderRefundService orderRefundService;
    private final RedisLock redisLock;

    public RefundCompensator(OrderRefundService orderRefundService, RedisLock redisLock) {
        this.orderRefundService = orderRefundService;
        this.redisLock = redisLock;
    }

    /** 每分钟扫一次；多实例用分布式锁串行化，避免同一批退款被并发重投 */
    @Scheduled(fixedDelay = 60000, initialDelay = 20000)
    public void compensate() {
        if (!redisLock.tryLock(LOCK_KEY, Duration.ZERO)) {
            return;
        }
        try {
            int handled = orderRefundService.retryPending(BATCH);
            if (handled > 0) {
                log.info("退款补偿任务本轮处理 {} 条记录", handled);
            }
        } catch (Exception e) {
            // 调度线程里的异常必须自己接住，否则这一轮静默失败，下一轮之前没人知道
            log.error("退款补偿任务执行失败", e);
        } finally {
            redisLock.unlock(LOCK_KEY);
        }
    }
}
