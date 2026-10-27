package com.sky.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类（jjwt 0.12 API）。
 */
public class JwtUtil {

    /** HS256 要求密钥不短于 256 bit，即至少 32 个字符 */
    private static final int MIN_SECRET_BYTES = 32;

    /** 签发方标识，解析时必须匹配，避免拿到别处签发的同算法 token 也能通过 */
    private static final String ISSUER = "sky-take-out";

    /**
     * 生成jwt
     * 使用Hs256算法
     *
     * @param secretKey jwt秘钥
     * @param ttlMillis jwt过期时间(毫秒)
     * @param claims    设置的信息
     * @return
     */
    public static String createJWT(String secretKey, long ttlMillis, Map<String, Object> claims) {
        SecretKey key = signingKey(secretKey);

        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claims(claims)
                .issuer(ISSUER)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Token解密
     *
     * @param secretKey jwt秘钥 此秘钥一定要保留好在服务端, 不能暴露出去, 否则sign就可以被伪造, 如果对接多个客户端建议改造成多个
     * @param token     加密后的token
     * @return
     */
    public static Claims parseJWT(String secretKey, String token) {
        JwtParser parser = Jwts.parser()
                .requireIssuer(ISSUER)
                .verifyWith(signingKey(secretKey))
                .build();
        return parser.parseSignedClaims(token).getPayload();
    }

    /**
     * 密钥长度不足时直接抛错，不做静默降级：签名密钥一旦过短就可以被字典穷举，
     * 任何持有一个 token 的人都能仿造同算法的新 token。
     */
    private static SecretKey signingKey(String secretKey) {
        byte[] bytes = secretKey == null ? new byte[0] : secretKey.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT 签名密钥长度不足：需要至少 " + MIN_SECRET_BYTES
                    + " 字节（当前 " + bytes.length + "），请通过环境变量注入足够长的随机密钥");
        }
        return Keys.hmacShaKeyFor(bytes);
    }

}
