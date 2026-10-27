package com.sky.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 员工密码哈希。
 * <p>
 * 原来是 MD5 不加盐：同一密码的哈希完全相同，一份彩虹表就能批量还原，
 * 而且员工改密码后旧哈希仍可比对。BCrypt 自带随机盐并且是慢哈希。
 */
public final class PasswordHasher {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /** 历史遗留的无盐 MD5 特征：32 位十六进制 */
    private static final Pattern LEGACY_MD5 = Pattern.compile("^[0-9a-fA-F]{32}$");

    private PasswordHasher() {
    }

    public static String hash(String rawPassword) {
        return ENCODER.encode(rawPassword);
    }

    /**
     * 校验密码。兼容库里仍是 MD5 的老数据，避免这次改造直接把已有账号锁死在门外。
     */
    public static boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null) {
            return false;
        }
        if (isLegacyMd5(storedHash)) {
            return legacyMd5(rawPassword).equalsIgnoreCase(storedHash);
        }
        return ENCODER.matches(rawPassword, storedHash);
    }

    /**
     * 该存储值是否还是无盐 MD5，需要在下次登录时就地升级为 BCrypt
     */
    public static boolean isLegacyMd5(String storedHash) {
        return storedHash != null && LEGACY_MD5.matcher(storedHash).matches();
    }

    private static String legacyMd5(String rawPassword) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5")
                    .digest(rawPassword.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
