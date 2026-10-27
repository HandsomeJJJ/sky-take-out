package com.sky.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket服务
 */
@Component
@ServerEndpoint("/ws/{sid}")
@Slf4j
public class WebSocketServer {

    //存放会话对象。@ServerEndpoint 每个连接一个新实例，但 sessionMap 是静态共享的，
    //必须用线程安全容器：Tomcat 会在多个线程上回调 onOpen/onClose/sendToAllClient。
    private static final Map<String, Session> SESSION_MAP = new ConcurrentHashMap<>();

    /**
     * 连接建立成功调用的方法
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("sid") String sid) {
        log.info("客户端：{}建立连接，当前在线连接数={}", sid, SESSION_MAP.size() + 1);
        SESSION_MAP.put(sid, session);
    }

    /**
     * 收到客户端消息后调用的方法
     *
     * @param message 客户端发送过来的消息
     */
    @OnMessage
    public void onMessage(String message, @PathParam("sid") String sid) {
        log.debug("收到来自客户端：{}的信息:{}", sid, message);
    }

    /**
     * 连接关闭调用的方法
     *
     * @param sid
     */
    @OnClose
    public void onClose(@PathParam("sid") String sid) {
        SESSION_MAP.remove(sid);
        log.info("连接断开:{}", sid);
    }

    /**
     * 群发
     *
     * @param message
     */
    public void sendToAllClient(String message) {
        Collection<Map.Entry<String, Session>> entries = SESSION_MAP.entrySet();
        for (Map.Entry<String, Session> entry : entries) {
            try {
                //服务器向客户端发送消息
                entry.getValue().getBasicRemote().sendText(message);
            } catch (Exception e) {
                // 发送失败的连接多半已经断了，留着既不释放也拖累后续推送，直接摘掉
                log.warn("WebSocket 推送失败，移除连接 sid={}", entry.getKey(), e);
                SESSION_MAP.remove(entry.getKey());
                closeQuietly(entry.getValue());
            }
        }
    }

    private void closeQuietly(Session session) {
        try {
            if (session != null && session.isOpen()) {
                session.close();
            }
        } catch (Exception e) {
            log.debug("关闭失效的 WebSocket 连接时出错", e);
        }
    }

}
