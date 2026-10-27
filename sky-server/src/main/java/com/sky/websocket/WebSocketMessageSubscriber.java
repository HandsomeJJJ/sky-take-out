package com.sky.websocket;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Redis Pub/Sub 订阅器：收到广播消息后，转发给本节点持有的 WebSocket 连接。
 * session 不可序列化、只能存本地，所以跨节点推送靠"广播消息 + 各节点本地转发"。
 */
@Component
public class WebSocketMessageSubscriber implements MessageListener {

    private final WebSocketServer webSocketServer;

    public WebSocketMessageSubscriber(WebSocketServer webSocketServer) {
        this.webSocketServer = webSocketServer;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        webSocketServer.sendToAllClient(new String(message.getBody(), StandardCharsets.UTF_8));
    }
}
