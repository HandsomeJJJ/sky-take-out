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

    /** 消费失败的消息落到这里，不再无限重投；队列需要有告警盯着 */
    public static final String CANCEL_DLX_EXCHANGE = "order.cancel.dlx";
    public static final String CANCEL_DLQ = "order.cancel.dlq";

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

    /**
     * 取消队列自身再挂一层死信：延迟到期用的是 CANCEL_EXCHANGE，
     * 消费失败要丢弃到 DLQ，两者的 exchange 必须分开。
     */
    @Bean
    public Queue orderCancelQueue() {
        return QueueBuilder.durable(CANCEL_QUEUE)
                .deadLetterExchange(CANCEL_DLX_EXCHANGE)
                .deadLetterRoutingKey(CANCEL_DLQ)
                .build();
    }

    @Bean
    public DirectExchange orderCancelDlxExchange() { return new DirectExchange(CANCEL_DLX_EXCHANGE); }

    @Bean
    public Queue orderCancelDeadLetterQueue() { return QueueBuilder.durable(CANCEL_DLQ).build(); }

    @Bean
    public Binding orderCancelDlqBinding() {
        return BindingBuilder.bind(orderCancelDeadLetterQueue())
                .to(orderCancelDlxExchange())
                .with(CANCEL_DLQ);
    }

    @Bean
    public Binding orderDelayBinding() {
        return BindingBuilder.bind(orderDelayQueue()).to(orderDelayExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Binding orderCancelBinding() {
        return BindingBuilder.bind(orderCancelQueue()).to(orderCancelExchange()).with(ROUTING_KEY);
    }
}
