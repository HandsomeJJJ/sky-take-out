package com.sky.messaging.rabbitmq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 使用 TTL + 死信队列实现订单延迟取消，不依赖延迟消息插件。 */
@Configuration
@ConditionalOnProperty(prefix = "sky.messaging.rabbitmq", name = "enabled", havingValue = "true")
public class RabbitMqConfiguration {
    public static final String DELAY_EXCHANGE = "order.delay.exchange";
    public static final String DELAY_QUEUE = "order.delay.queue";
    public static final String CANCEL_EXCHANGE = "order.cancel.exchange";
    public static final String CANCEL_QUEUE = "order.cancel.queue";
    public static final String ROUTING_KEY = "order.timeout";

    @Bean
    public DirectExchange orderDelayExchange() { return new DirectExchange(DELAY_EXCHANGE); }

    @Bean
    public DirectExchange orderCancelExchange() { return new DirectExchange(CANCEL_EXCHANGE); }

    @Bean
    public Queue orderDelayQueue() {
        return QueueBuilder.durable(DELAY_QUEUE)
                .ttl(15 * 60 * 1000)
                .deadLetterExchange(CANCEL_EXCHANGE)
                .deadLetterRoutingKey(ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue orderCancelQueue() { return QueueBuilder.durable(CANCEL_QUEUE).build(); }

    @Bean
    public Binding orderDelayBinding() {
        return BindingBuilder.bind(orderDelayQueue()).to(orderDelayExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Binding orderCancelBinding() {
        return BindingBuilder.bind(orderCancelQueue()).to(orderCancelExchange()).with(ROUTING_KEY);
    }
}
