package com.sky.websocket;

import com.sky.constant.JwtClaimsConstant;
import com.sky.properties.JwtProperties;
import com.sky.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocket 握手鉴权。
 * <p>
 * {@code /ws/{sid}} 是管理端的订单播报通道：不校验令牌的话，任何知道地址的人连上就能收到
 * 别人店铺的订单推送。这里要求令牌必须由管理端密钥签发，且带 empId 声明 ——
 * 用户端令牌是另一套密钥，签不出来。
 */
class WebSocketAuthenticatorTest {

    private static final String ADMIN_SECRET = "test-admin-secret-key-at-least-32-bytes-long";
    private static final String USER_SECRET = "test-user-secret-key-at-least-32-bytes-long!!";

    private WebSocketAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setAdminSecretKey(ADMIN_SECRET);
        properties.setUserSecretKey(USER_SECRET);
        authenticator = new WebSocketAuthenticator(properties);
    }

    @Test
    @DisplayName("管理端令牌放行")
    void acceptsAdminToken() {
        String token = token(ADMIN_SECRET, JwtClaimsConstant.EMP_ID, "1");

        assertThat(authenticator.isAuthorized("token=" + encode(token))).isTrue();
    }

    @Test
    @DisplayName("令牌可以出现在查询串的任意位置")
    void acceptsTokenAfterOtherParams() {
        String token = token(ADMIN_SECRET, JwtClaimsConstant.EMP_ID, "1");

        assertThat(authenticator.isAuthorized("sid=merchant-1&token=" + encode(token))).isTrue();
    }

    @Test
    @DisplayName("不带令牌直接拒绝")
    void rejectsMissingToken() {
        assertThat(authenticator.isAuthorized(null)).isFalse();
        assertThat(authenticator.isAuthorized("")).isFalse();
        assertThat(authenticator.isAuthorized("sid=merchant-1")).isFalse();
        assertThat(authenticator.isAuthorized("token=")).isFalse();
    }

    @Test
    @DisplayName("用户端令牌打不进管理端通道：密钥不同，验签就过不了")
    void rejectsUserToken() {
        String token = token(USER_SECRET, JwtClaimsConstant.USER_ID, "9");

        assertThat(authenticator.isAuthorized("token=" + encode(token))).isFalse();
    }

    @Test
    @DisplayName("管理端密钥签发但没有 empId 的令牌同样拒绝")
    void rejectsTokenWithoutEmpId() {
        String token = token(ADMIN_SECRET, JwtClaimsConstant.USER_ID, "9");

        assertThat(authenticator.isAuthorized("token=" + encode(token))).isFalse();
    }

    @Test
    @DisplayName("伪造/损坏的令牌不会让握手继续，也不会把异常抛给容器")
    void rejectsGarbageToken() {
        assertThat(authenticator.isAuthorized("token=not-a-jwt")).isFalse();
        // 只匹配名为 token 的参数，其它同名前缀的参数（mytoken=）不算
        assertThat(authenticator.isAuthorized("mytoken=" + token(ADMIN_SECRET,
                JwtClaimsConstant.EMP_ID, "1"))).isFalse();
    }

    private static String token(String secret, String claimName, Object claimValue) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(claimName, claimValue);
        return JwtUtil.createJWT(secret, 60_000, claims);
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
