package com.sky.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.sky.event.OrderCreatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MQ 报文与第三方响应解析都走这个工具类，round-trip 正确性是硬要求。
 */
class JsonUtilsTest {

    @Test
    @DisplayName("订单事件序列化后能原样反序列化，金额与时间不丢精度")
    void orderEventRoundTrip() {
        OrderCreatedEvent event = new OrderCreatedEvent(1L, "202610280001", 100L,
                new BigDecimal("43.50"), LocalDateTime.of(2026, 10, 28, 12, 30, 15));

        String json = JsonUtils.toJson(event);
        OrderCreatedEvent parsed = JsonUtils.toObject(json, OrderCreatedEvent.class);

        assertThat(parsed.getOrderId()).isEqualTo(1L);
        assertThat(parsed.getOrderNumber()).isEqualTo("202610280001");
        assertThat(parsed.getAmount()).isEqualByComparingTo("43.50");
        // 秒级精度必须保住：报文若按分钟格式化，超时判定会差几十秒
        assertThat(parsed.getOrderTime()).isEqualTo(LocalDateTime.of(2026, 10, 28, 12, 30, 15));
    }

    @Test
    @DisplayName("解析第三方报文时忽略未知字段，微信加字段不会打挂登录")
    void ignoresUnknownFields() {
        String wechatResponse = "{\"openid\":\"o-abc\",\"session_key\":\"k\",\"unionid\":\"u\",\"new_thing\":1}";

        JsonNode node = JsonUtils.readTree(wechatResponse);

        assertThat(node.path("openid").asText()).isEqualTo("o-abc");
        assertThat(node.path("missing").asText(null)).isNull();
    }

    @Test
    @DisplayName("坏 JSON 报出可读异常")
    void parseFailureIsDescriptive() {
        assertThatThrownBy(() -> JsonUtils.readTree("<html>502 Bad Gateway</html>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 解析失败");
    }
}
