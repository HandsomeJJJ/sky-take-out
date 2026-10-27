package com.sky.messaging;

import com.sky.entity.MqMessage;
import com.sky.filter.TraceIdFilter;
import com.sky.lock.RedisLock;
import com.sky.mapper.MqMessageMapper;
import com.sky.messaging.rabbitmq.RabbitMqConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地消息表发送器：从 mq_message 拉取待发送记录，按业务类型投递到 RabbitMQ / Kafka。
 * <p>
 * 三条规矩：
 * 1. 先"认领"再发送 —— 集群多实例扫到同一批记录时只会有一个实例真正投递；
 * 2. 拿到 broker 确认之后才标已发送 —— 否则"已发送"只代表消息进了本机缓冲区；
 * 3. 失败退避重试，超上限转死亡并告警 —— 死亡记录需要人工介入，不能静默堆积。
 */
@Component
@Slf4j
public class MqMessageSender {

    public static final String BIZ_ORDER_TIMEOUT = "ORDER_TIMEOUT_CANCEL";
    public static final String BIZ_ORDER_STATS = "ORDER_STATS";

    public static final String KAFKA_TOPIC_ORDER_CREATED = "order.created";

    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRY = 5;
    /** 等待 broker 确认的超时 */
    private static final long CONFIRM_TIMEOUT_MS = 5000;
    /** "发送中"状态超过这个时长视为持有者已崩溃 */
    private static final Duration SENDING_STALE = Duration.ofMinutes(5);

    /** 整个扫描过程串行化，避免多个实例/线程同时处理同一批记录 */
    private static final String SENDER_LOCK_KEY = "mq:sender:lock";

    private final MqMessageMapper mqMessageMapper;
    private final RabbitTemplate rabbitTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RedisLock redisLock;
    private final MessagingSwitch messagingSwitch;

    public MqMessageSender(MqMessageMapper mqMessageMapper,
                           RabbitTemplate rabbitTemplate,
                           KafkaTemplate<String, String> kafkaTemplate,
                           RedisLock redisLock,
                           MessagingSwitch messagingSwitch) {
        this.mqMessageMapper = mqMessageMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.redisLock = redisLock;
        this.messagingSwitch = messagingSwitch;
    }

    /**
     * 扫描并发送待发送消息。事务提交后立即调用一次 + 定时补偿调用。
     */
    public void sendPending() {
        if (!messagingSwitch.anyEnabled()) {
            log.debug("Kafka / RabbitMQ 均未启用，跳过本地消息发送");
            return;
        }
        // 不等待：拿不到锁说明别的实例正在发，直接跳过（重复投递只会给消费者制造幂等负担）
        if (!redisLock.tryLock(SENDER_LOCK_KEY, Duration.ZERO)) {
            log.debug("已有实例在发送本地消息，本次跳过");
            return;
        }
        try {
            int reclaimed = mqMessageMapper.reclaimStuck(LocalDateTime.now().minus(SENDING_STALE));
            if (reclaimed > 0) {
                log.warn("回收了 {} 条卡在发送中的消息（持有者可能已崩溃）", reclaimed);
            }

            List<MqMessage> pending = mqMessageMapper.listPending(MqMessage.PENDING, BATCH_SIZE);
            for (MqMessage msg : pending) {
                if (!messagingSwitch.isEnabledFor(msg.getBizType())) {
                    continue;
                }
                // 认领失败 = 这条已被别的实例拿走
                if (mqMessageMapper.claim(msg.getId()) != 1) {
                    continue;
                }
                sendOne(msg);
            }
        } finally {
            redisLock.unlock(SENDER_LOCK_KEY);
        }
    }

    private void sendOne(MqMessage msg) {
        try {
            doSend(msg);
            mqMessageMapper.markSent(msg.getId());
            log.info("消息已送达 broker，id={}, bizType={}", msg.getId(), msg.getBizType());
        } catch (Exception e) {
            log.error("消息发送失败，id={}, bizType={}, retry={}", msg.getId(), msg.getBizType(), msg.getRetryCount(), e);
            mqMessageMapper.incrRetry(msg.getId(), nextRetryTime(msg.getRetryCount()));
            int dead = mqMessageMapper.markDead(msg.getId(), MAX_RETRY);
            if (dead == 1) {
                // 到这里消息不会再自动重发，必须留下可被告警捞出来的痕迹
                log.error("本地消息超过最大重试次数进入死亡态，需要人工处理。id={}, bizType={}, bizId={}",
                        msg.getId(), msg.getBizType(), msg.getBizId());
            }
        }
    }

    private void doSend(MqMessage msg) throws Exception {
        if (BIZ_ORDER_TIMEOUT.equals(msg.getBizType())) {
            sendToRabbit(msg);
        } else if (BIZ_ORDER_STATS.equals(msg.getBizType())) {
            sendToKafka(msg);
        } else {
            throw new IllegalArgumentException("未知消息类型：" + msg.getBizType());
        }
    }

    /**
     * RabbitMQ：在同一个 channel 内发送并同步等待 publisher confirm。
     * 需要 spring.rabbitmq.publisher-confirm-type=correlated，否则 waitForConfirms 拿不到结果。
     */
    private void sendToRabbit(MqMessage msg) {
        Boolean confirmed = rabbitTemplate.invoke(operations -> {
            operations.convertAndSend(RabbitMqConfiguration.DELAY_EXCHANGE,
                    RabbitMqConfiguration.ROUTING_KEY, msg.getPayload(), message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setHeader(MessageTrace.HEADER, traceId());
                        return message;
                    });
            return operations.waitForConfirms(CONFIRM_TIMEOUT_MS);
        });
        if (!Boolean.TRUE.equals(confirmed)) {
            throw new IllegalStateException("RabbitMQ 未确认投递，timeout=" + CONFIRM_TIMEOUT_MS + "ms");
        }
    }

    /**
     * Kafka：send 是异步的，必须等 future 完成才算送达 broker。
     * 原来发完就走并把记录标成"已发送"，broker 不可用时消息实际一条都没出去。
     */
    private void sendToKafka(MqMessage msg) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                KAFKA_TOPIC_ORDER_CREATED, null, msg.getBizId(), msg.getPayload());
        record.headers().add(new RecordHeader(MessageTrace.HEADER, traceId().getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(record).get(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private String traceId() {
        String traceId = MessageTrace.current();
        return traceId == null ? MessageTrace.newTraceId() : traceId;
    }

    /** 指数退避：2^retry 分钟，上限 1 小时 */
    private LocalDateTime nextRetryTime(int retryCount) {
        long minutes = Math.min((long) Math.pow(2, Math.max(retryCount, 0)), 60);
        return LocalDateTime.now().plus(Duration.ofMinutes(minutes));
    }
}
