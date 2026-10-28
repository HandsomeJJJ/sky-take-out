package com.sky.messaging.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka 消费失败的兜底：重试若干次后转死信 topic，而不是把消息吃掉。
 * <p>
 * spring-kafka 的默认错误处理在重试耗尽后只打一行日志然后照常提交 offset ——
 * 对"订单统计"这种算少一次就是数据错消费方来说，等于静默丢数据，而且事后无从追查。
 * 这里显式装上 DLT：重试次数、退避步长、落点 topic 都可控，
 * 死信 topic 因为项目关掉了 broker 的 auto-create，必须跟着一起显式声明。
 */
@Configuration
@ConditionalOnProperty(prefix = "sky.messaging.kafka", name = "enabled", havingValue = "true")
public class KafkaReliabilityConfiguration {

    /** 订单创建事件的死信 topic；名字固定，监控可以直接按 topic 配告警 */
    public static final String ORDER_CREATED_DLT = "order.created.DLT";

    /**
     * 重试节奏：首次 1 秒、倍数 2、单次最长 8 秒、总耗时上限 15 秒 —— 大约 4 次重试后转死信。
     * spring-core 的 ExponentialBackOff 只给"最长总时长"这一个上界（没有 maxAttempts），
     * 所以次数是靠 maxElapsedTime 间接限制的，写清楚免得以为漏配。
     */
    private static final long INITIAL_INTERVAL_MS = 1000L;
    private static final double MULTIPLIER = 2.0;
    private static final long MAX_INTERVAL_MS = 8000L;
    private static final long MAX_ELAPSED_MS = 15000L;

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception exception) ->
                        new TopicPartition(record.topic() + ".DLT", record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        backOff.setMultiplier(MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);
        backOff.setMaxElapsedTime(MAX_ELAPSED_MS);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // 反序列化失败与报文结构错误是"重试多少次都一样"的错误，直接进死信，不占用重试次数
        handler.addNotRetryableExceptions(SerializationException.class, IllegalArgumentException.class);
        return handler;
    }

    /**
     * 把上面的错误处理器接到监听容器上。Boot 只在配置里给了开关时才会自动装，
     * 这里显式覆盖 factory（bean 名与 Boot 的默认名一致即可顶掉它），
     * 保证 @KafkaListener 抛出的异常真的走重试 + 死信，而不是被默认策略吞掉。
     */
    @Bean
    public KafkaListenerContainerFactory<?> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            CommonErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
