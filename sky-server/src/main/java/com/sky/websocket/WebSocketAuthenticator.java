package com.sky.websocket;

import com.sky.constant.JwtClaimsConstant;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket 握手的鉴权器。
 * <p>
 * {@code @ServerEndpoint} 的实例由容器按连接创建，不是 Spring Bean，
 * 所以这里由 Spring 管理的实现把自己塞到 {@link WebSocketServer} 的静态字段上，
 * 连接建立时再回调校验。
 */
@Component
@Slf4j
public class WebSocketAuthenticator {

    private static final String QUERY_TOKEN_KEY = "token";

    private final JwtProperties jwtProperties;

    public WebSocketAuthenticator(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @PostConstruct
    public void register() {
        WebSocketServer.setAuthenticator(this);
    }

    /**
     * 校验握手请求。
     *
     * @param queryString 形如 {@code token=xxx&sid=...} 的查询串
     * @return 是否是合法的管理端连接
     */
    public boolean isAuthorized(String queryString) {
        String token = tokenOf(queryString);
        if (!StringUtils.hasText(token)) {
            log.warn("WebSocket 连接未携带令牌，拒绝建立连接");
            return false;
        }
        try {
            Claims claims = JwtUtil.parseJWT(jwtProperties.getAdminSecretKey(), token);
            // 只验签不够：用户端令牌同样能验签通过（不同密钥），这里要求必须是管理端令牌
            return claims.get(JwtClaimsConstant.EMP_ID) != null;
        } catch (Exception e) {
            log.warn("WebSocket 令牌校验失败：{}", e.getMessage());
            return false;
        }
    }

    private static String tokenOf(String queryString) {
        if (!StringUtils.hasText(queryString)) {
            return null;
        }
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !QUERY_TOKEN_KEY.equals(pair.substring(0, eq))) {
                continue;
            }
            String value = pair.substring(eq + 1);
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                return value;
            }
        }
        return null;
    }
}
