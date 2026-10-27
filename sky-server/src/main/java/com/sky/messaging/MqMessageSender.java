package com.sky.messaging;

import com.sky.entity.MqMessage;
import com.sky.mapper.MqMessageMapper;
import com.sky.messaging.rabbitmq.RabbitMqConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表发送器：从 mq_message 拉取待发送记录，按业务类型投递到 RabbitMQ / Kafka。
 * 发送成功标记已发送，失败记录重试次数并退避，超过上限标记死亡。
 */
@Component
@Slf4j
public class MqMessageSender {

    public static final String BIZ_ORDER_TIMEOUT = "ORDER_TIMEOUT_CANCEL";
    public static final String BIZ_ORDER_STATS = "ORDER_STATS";

    public static final String KAFKA_TOPIC_ORDER_CREATED = "order.created";

    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRY = 5;

    private final MqMessageMapper mqMessageMapper;
    private final RabbitTemplate rabbitTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public MqMessageSender(MqMessageMapper mqMessageMapper,
                           RabbitTemplate rabbitTemplate,
                           KafkaTemplate<String, String> kafkaTemplate) {
        this.mqMessageMapper = mqMessageMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 扫描并发送待发送消息。事务提交后立即调用一次 + 定时补偿调用。
     */
    public void sendPending() {
        List<MqMessage> pending = mqMessageMapper.listPending(MqMessage.PENDING, BATCH_SIZE);
        for (MqMessage msg : pending) {
            try {
                doSend(msg);
                mqMessageMapper.markSent(msg.getId());
                log.info("消息发送成功，id={}, bizType={}", msg.getId(), msg.getBizType());
            } catch (Exception e) {
                log.error("消息发送失败，id={}, bizType={}, retry={}", msg.getId(), msg.getBizType(), msg.getRetryCount(), e);
                LocalDateTime nextRetry = nextRetryTime(msg.getRetryCount());
                mqMessageMapper.incrRetry(msg.getId(), nextRetry);
                mqMessageMapper.markDead(msg.getId(), MAX_RETRY);
            }
        }
    }

    private void doSend(MqMessage msg) {
        if (BIZ_ORDER_TIMEOUT.equals(msg.getBizType())) {
            // 订单超时取消：投递到 RabbitMQ 延迟队列（TTL 15 分钟后转死信触发取消）
            rabbitTemplate.convertAndSend(RabbitMqConfiguration.DELAY_EXCHANGE,
                    RabbitMqConfiguration.ROUTING_KEY, msg.getPayload());
        } else if (BIZ_ORDER_STATS.equals(msg.getBizType())) {
            // 订单统计：投递到 Kafka，key=订单号保证同订单有序
            kafkaTemplate.send(KAFKA_TOPIC_ORDER_CREATED, msg.getBizId(), msg.getPayload());
        } else {
            throw new IllegalArgumentException("未知消息类型：" + msg.getBizType());
        }
    }

    /** 指数退避：2^retry 分钟，上限 1 小时 */
    private LocalDateTime nextRetryTime(int retryCount) {
        long minutes = Math.min((long) Math.pow(2, retryCount), 60);
        return LocalDateTime.now().plus(Duration.ofMinutes(minutes));
    }
}
