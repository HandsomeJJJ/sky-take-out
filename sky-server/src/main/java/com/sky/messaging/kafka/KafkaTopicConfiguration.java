package com.sky.messaging.kafka;

import com.sky.messaging.MqMessageSender;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 显式建 topic。
 * 依赖 broker 的 auto-create 意味着分区数、副本数由 broker 默认值决定，
 * 换一套环境行为就变了，而且第一条消息之前的分区分配是随机的。
 */
@Configuration
@ConditionalOnProperty(prefix = "sky.messaging.kafka", name = "enabled", havingValue = "true")
public class KafkaTopicConfiguration {

    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(MqMessageSender.KAFKA_TOPIC_ORDER_CREATED)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
