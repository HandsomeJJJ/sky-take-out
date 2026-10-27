package com.sky.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MQ 开关。两个中间件在本地默认关闭（不起容器也能跑），
 * 但关闭时不能再往本地消息表里塞注定发不出去的记录 ——
 * 否则每笔订单都会攒出两条消息，退避 5 次后集体转死亡态，把死亡表变成噪音表。
 */
@Component
@Slf4j
public class MessagingSwitch {

    private final boolean kafkaEnabled;
    private final boolean rabbitEnabled;

    public MessagingSwitch(@Value("${sky.messaging.kafka.enabled:false}") boolean kafkaEnabled,
                           @Value("${sky.messaging.rabbitmq.enabled:false}") boolean rabbitEnabled) {
        this.kafkaEnabled = kafkaEnabled;
        this.rabbitEnabled = rabbitEnabled;
        log.info("消息能力开关：kafka={}, rabbitmq={}", kafkaEnabled, rabbitEnabled);
    }

    public boolean anyEnabled() {
        return kafkaEnabled || rabbitEnabled;
    }

    /** 该业务类型的目标 MQ 是否启用 */
    public boolean isEnabledFor(String bizType) {
        if (MqMessageSender.BIZ_ORDER_TIMEOUT.equals(bizType)) {
            return rabbitEnabled;
        }
        if (MqMessageSender.BIZ_ORDER_STATS.equals(bizType)) {
            return kafkaEnabled;
        }
        return false;
    }
}
