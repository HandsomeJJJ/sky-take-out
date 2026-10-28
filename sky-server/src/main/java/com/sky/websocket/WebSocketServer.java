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

    //握手鉴权器由 Spring 侧的 WebSocketAuthenticator 在初始化时注入：
    //@ServerEndpoint 的实例是容器按连接 new 出来的，拿不到 Spring 容器，只能走静态引用。
    private static volatile WebSocketAuthenticator authenticator;

    static void setAuthenticator(WebSocketAuthenticator authenticator) {
        WebSocketServer.authenticator = authenticator;
    }

    /**
     * 连接建立成功调用的方法
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("sid") String sid) {
        //地址是 ws://host/ws/{sid}?token=xxx，任意客户端都能连上这个广播通道拿到订单推送，
        //所以握手先验令牌；鉴权器还没注册说明装配失败，宁可拒绝也不能裸奔。
        if (authenticator == null) {
            log.error("WebSocket 鉴权器未注册，拒绝连接 sid={}", sid);
            closeQuietly(session);
            return;
        }
        String query = session.getRequestURI() == null ? null : session.getRequestURI().getQuery();
        if (!authenticator.isAuthorized(query)) {
            log.warn("客户端：{}握手鉴权失败，关闭连接", sid);
            closeQuietly(session);
            return;
        }

        //同一 sid 重连时先摘掉旧会话，否则旧连接断开会把新会话误删
        Session previous = SESSION_MAP.put(sid, session);
        closeQuietly(previous);
        log.info("客户端：{}建立连接，当前在线连接数={}", sid, SESSION_MAP.size());
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
    public void onClose(Session session, @PathParam("sid") String sid) {
        //重连后旧会话的 close 回调可能晚于新会话的 open，按值删除才不会把新会话摘掉
        SESSION_MAP.remove(sid, session);
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
