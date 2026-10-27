package com.sky.task;

import com.sky.messaging.MqMessageSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 本地消息表补偿任务：定时扫描未发送成功的消息并重发。
 * 事务提交后的立即发送只是"尽早发送"，真正保证不丢消息的是这个兜底。
 */
@Component
@Slf4j
public class MqMessageCompensator {

    private final MqMessageSender mqMessageSender;

    public MqMessageCompensator(MqMessageSender mqMessageSender) {
        this.mqMessageSender = mqMessageSender;
    }

    /** 每 30 秒补偿一次 */
    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void compensate() {
        mqMessageSender.sendPending();
    }
}
