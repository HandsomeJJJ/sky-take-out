package com.sky.websocket;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * WebSocket 消息发布器：把消息广播到 Redis Pub/Sub 频道。
 * 多实例部署时，支付回调可能落在任意节点，通过 Redis 广播让"真正持有管理端连接"的节点
 * 收到消息后推送给本地 session，解决单机 Map 导致的推送丢失。
 */
@Component
public class WebSocketMessagePublisher {

    public static final String CHANNEL = "websocket:broadcast";

    private final StringRedisTemplate stringRedisTemplate;

    public WebSocketMessagePublisher(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void broadcast(String message) {
        stringRedisTemplate.convertAndSend(CHANNEL, message);
    }
}
