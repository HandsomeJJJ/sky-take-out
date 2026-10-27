package com.sky.config;

import com.sky.properties.JwtProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;

/**
 * 启动自检：把"配置只在本地是对的、被原样带到线上"这类静默风险喊出来。
 */
@Component
@Slf4j
public class StartupGuard {

    /** dev profile 里的本地占位密钥特征串 */
    private static final String LOCAL_PLACEHOLDER = "local-dev-only";

    private final JwtProperties jwtProperties;
    private final Environment environment;

    public StartupGuard(JwtProperties jwtProperties, Environment environment) {
        this.jwtProperties = jwtProperties;
        this.environment = environment;
    }

    @PostConstruct
    public void check() {
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        boolean usingPlaceholder = containsPlaceholder(jwtProperties.getAdminSecretKey())
                || containsPlaceholder(jwtProperties.getUserSecretKey());

        if (usingPlaceholder && !activeProfiles.contains("dev")) {
            // 占位密钥是公开的，任何拿到源码的人都能签出合法令牌
            throw new IllegalStateException("检测到 JWT 签名密钥仍是仓库内的本地占位值，"
                    + "非 dev profile 下拒绝启动。请设置 JWT_ADMIN_SECRET_KEY / JWT_USER_SECRET_KEY（>=32 字节随机值）");
        }
        if (usingPlaceholder) {
            log.warn("当前使用仓库内的本地占位 JWT 密钥，仅限开发；任何对外环境必须替换");
        }
        log.info("启动自检完成，activeProfiles={}", activeProfiles);
    }

    private static boolean containsPlaceholder(String secret) {
        return secret != null && secret.contains(LOCAL_PLACEHOLDER);
    }
}
