package com.sky.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码哈希：BCrypt 校验 + 历史无盐 MD5 的就地升级判定。
 */
class PasswordHasherTest {

    /** DigestUtils.md5DigestAsHex("123456") 的结果 */
    private static final String LEGACY_MD5_OF_123456 = "e10adc3949ba59abbe56e057f20f883e";

    @Test
    @DisplayName("BCrypt 哈希可校验，且同一明文每次摘要不同（自带随机盐）")
    void bcryptRoundTrip() {
        String first = PasswordHasher.hash("123456");
        String second = PasswordHasher.hash("123456");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).hasSize(60).startsWith("$2");
        assertThat(PasswordHasher.matches("123456", first)).isTrue();
        assertThat(PasswordHasher.matches("1234567", first)).isFalse();
    }

    @Test
    @DisplayName("库里遗留的 32 位 MD5 仍能登录，并被识别为需要升级")
    void legacyMd5StillWorksAndIsDetected() {
        assertThat(PasswordHasher.isLegacyMd5(LEGACY_MD5_OF_123456)).isTrue();
        assertThat(PasswordHasher.matches("123456", LEGACY_MD5_OF_123456)).isTrue();
        assertThat(PasswordHasher.matches("wrong", LEGACY_MD5_OF_123456)).isFalse();

        // 升级成 BCrypt 之后不再是 MD5 形态，避免每次登录都重写一遍
        assertThat(PasswordHasher.isLegacyMd5(PasswordHasher.hash("123456"))).isFalse();
    }

    @Test
    @DisplayName("空值不能通过校验")
    void nullSafe() {
        assertThat(PasswordHasher.matches(null, LEGACY_MD5_OF_123456)).isFalse();
        assertThat(PasswordHasher.matches("123456", null)).isFalse();
    }
}
