package com.sky.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.sky.cache.JitteredTtlRedisCacheWriter;
import com.sky.json.JacksonObjectMapper;
import com.sky.websocket.WebSocketMessagePublisher;
import com.sky.websocket.WebSocketMessageSubscriber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@Slf4j
public class RedisConfiguration {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory redisConnectionFactory){
        log.info("开始创建redis模板对象...");
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        //设置redis的连接工厂对象
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        //设置redis key的序列化器
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        // value 用 JSON：JDK 序列化存在反序列化 RCE 风险，且跨语言不可读、体积大
        redisTemplate.setValueSerializer(jsonSerializer());
        // hash 系列要单独指定，否则 opsForHash 仍走默认的 JDK 序列化
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(jsonSerializer());
        return redisTemplate;
    }

    /**
     * 缓存管理器：JSON 序列化 + 带抖动的 TTL + 允许缓存空值（防缓存穿透）。
     * <p>
     * 两层防护：
     * 1. **雪崩** —— TTL 不再是所有 key 同一个 60 分钟，而是每个 key 在 ±20% 里浮动，
     *    避免集体过期后第一波请求全打到数据库；
     * 2. **击穿** —— 底层用 lockingRedisCacheWriter，配合读接口上的 {@code @Cacheable(sync = true)}，
     *    热点 key 过期时只有一个线程回源，其余线程等它写回，而不是全部涌向数据库。
     * <p>
     * 用 TransactionAwareCacheManagerProxy 再包一层，让 @CacheEvict 延迟到事务提交后再删缓存，
     * 避免"事务还没提交就删了缓存，并发请求把旧数据回填进缓存"的脏数据问题。
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory redisConnectionFactory){
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(60))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonSerializer()))
                .computePrefixWith(cacheName -> cacheName + "::");

        RedisCacheManager redisCacheManager = RedisCacheManager.builder(
                        JitteredTtlRedisCacheWriter.locking(redisConnectionFactory))
                .cacheDefaults(config)
                .build();

        return new TransactionAwareCacheManagerProxy(redisCacheManager);
    }

    /**
     * Redis Pub/Sub 监听容器：订阅 WebSocket 广播频道，实现多实例消息推送。
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory redisConnectionFactory,
            WebSocketMessageSubscriber webSocketMessageSubscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener(webSocketMessageSubscriber, new ChannelTopic(WebSocketMessagePublisher.CHANNEL));
        return container;
    }

    /**
     * 复用项目的 JacksonObjectMapper（保证 LocalDateTime 等 JDK8 时间类型正确序列化），
     * 并手动开启 default typing —— 否则反序列化回来是 LinkedHashMap 而不是原类型。
     * 同时限定只反序列化 com.sky / java 基础包下的类，收窄反序列化攻击面。
     */
    private GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper mapper = new JacksonObjectMapper();
        PolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.sky")
                .allowIfSubType("java.util")
                .allowIfSubType("java.lang")
                .allowIfSubType("java.math")
                .build();
        mapper.activateDefaultTyping(validator, ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
