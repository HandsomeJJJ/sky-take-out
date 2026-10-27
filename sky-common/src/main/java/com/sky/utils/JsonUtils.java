package com.sky.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 统一 JSON 出入口。原先散落 fastjson 1.2.76，其 AutoType 绕过系列 CVE 需要长期盯版本，
 * 且解析的对象正是微信回调、MQ 报文这类外部可控内容，故收敛到 Jackson 单点。
 */
public final class JsonUtils {

    /**
     * 与 Web 层的 JacksonObjectMapper 分开：那个把 LocalDateTime 格式化到分钟精度（给前端展示用），
     * 而 MQ 报文和第三方响应需要完整的秒级时间戳，不能共享同一个 formatter。
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private JsonUtils() {
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败：" + e.getMessage(), e);
        }
    }

    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败：" + abbreviate(json), e);
        }
    }

    public static <T> T toObject(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化为 " + type.getSimpleName() + " 失败：" + abbreviate(json), e);
        }
    }

    private static String abbreviate(String json) {
        if (json == null) {
            return "null";
        }
        return json.length() <= 200 ? json : json.substring(0, 200) + "...";
    }
}
