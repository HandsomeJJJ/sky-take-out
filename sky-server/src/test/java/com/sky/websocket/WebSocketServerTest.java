package com.sky.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.net.URI;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 握手鉴权的落点：@ServerEndpoint 不是 Spring Bean，只能靠静态注入的鉴权器。
 * 锁三条：未通过的连接被关闭且不进群发列表；通过的连接才收得到推送；鉴权器没注册时一律拒绝。
 */
@ExtendWith(MockitoExtension.class)
class WebSocketServerTest {

    @Mock
    private Session session;
    @Mock
    private RemoteEndpoint.Basic basic;
    @Mock
    private WebSocketAuthenticator authenticator;

    @AfterEach
    void tearDown() {
        WebSocketServer.setAuthenticator(null);
    }

    @Test
    @DisplayName("鉴权失败的连接被关闭，也不会被登记进群发列表")
    void closesUnauthenticatedHandshake() throws Exception {
        WebSocketServer server = new WebSocketServer();
        WebSocketServer.setAuthenticator(authenticator);
        when(session.getRequestURI()).thenReturn(new URI("http://localhost/ws/merchant-1"));
        when(authenticator.isAuthorized(null)).thenReturn(false);
        when(session.isOpen()).thenReturn(true);

        server.onOpen(session, "merchant-1");

        verify(session).close();
        verify(session, never()).getBasicRemote();

        // 没被登记的会话收不到推送：群发只会走已建立的连接
        server.sendToAllClient("payload");
        verify(session, never()).getBasicRemote();
    }

    @Test
    @DisplayName("鉴权通过的连接才建立会话，推送走它的 basic remote")
    void registersAuthenticatedHandshake() throws Exception {
        WebSocketServer server = new WebSocketServer();
        WebSocketServer.setAuthenticator(authenticator);
        when(session.getRequestURI()).thenReturn(new URI("http://localhost/ws/merchant-2?token=abc"));
        when(authenticator.isAuthorized("token=abc")).thenReturn(true);
        when(session.getBasicRemote()).thenReturn(basic);

        server.onOpen(session, "merchant-2");

        server.sendToAllClient("payload");

        verify(basic).sendText("payload");
        verify(session, never()).close();
    }

    @Test
    @DisplayName("鉴权器还没注册（装配失败）时拒绝连接，不裸奔")
    void rejectsWhenAuthenticatorMissing() throws Exception {
        WebSocketServer server = new WebSocketServer();
        WebSocketServer.setAuthenticator(null);
        when(session.isOpen()).thenReturn(true);

        server.onOpen(session, "merchant-3");

        verify(session).close();
        verify(session, never()).getBasicRemote();
    }
}
