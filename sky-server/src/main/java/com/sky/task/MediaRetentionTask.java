package com.sky.task;

import com.sky.lock.RedisLock;
import com.sky.service.MediaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 对象存储回收任务：把"已无引用且过了保留期"的图片真的从 bucket 删掉。
 * <p>
 * 没有这一步，bucket 里只有增没有减 —— 下架商品、换掉的旧图会永久留着。
 */
@Component
@Slf4j
public class MediaRetentionTask {

    private static final String LOCK_KEY = "media:retention:lock";

    private final MediaService mediaService;
    private final RedisLock redisLock;

    public MediaRetentionTask(MediaService mediaService, RedisLock redisLock) {
        this.mediaService = mediaService;
        this.redisLock = redisLock;
    }

    /** 每天凌晨 3:30 跑一次；多实例用分布式锁，避免同一批对象被并发删除 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void purge() {
        if (!redisLock.tryLock(LOCK_KEY, Duration.ZERO)) {
            return;
        }
        try {
            int deleted = mediaService.purgeExpired();
            if (deleted > 0) {
                log.info("对象存储回收完成，本次删除 {} 个文件", deleted);
            }
        } catch (Exception e) {
            log.error("对象存储回收任务执行失败", e);
        } finally {
            redisLock.unlock(LOCK_KEY);
        }
    }
}
