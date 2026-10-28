package com.sky.controller.nofity;

import com.sky.utils.JsonUtils;
import org.apache.http.entity.ContentType;
import org.springframework.http.HttpHeaders;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信回调的报文读取与应答。支付回调和退款回调的应答规则完全一致，
 * 抽出来避免两处各写一份、改一处漏一处。
 */
final class NotifyBodyReader {

    private NotifyBodyReader() {
    }

    static String read(HttpServletRequest request) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (result.length() > 0) {
                    result.append("\n");
                }
                result.append(line);
            }
        }
        return result.toString();
    }

    /**
     * 只有 HTTP 200 + code=SUCCESS 微信才认为通知已送达；
     * 处理失败必须返回非 200 + FAIL，否则微信停止重试，订单/退款会永久停在中间态。
     */
    static void respond(HttpServletResponse response, boolean success, String message) throws IOException {
        response.setStatus(success ? HttpServletResponse.SC_OK : HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", success ? "SUCCESS" : "FAIL");
        body.put("message", success ? "成功" : (message == null ? "处理失败" : message));
        response.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());
        response.getOutputStream().write(JsonUtils.toJson(body).getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }
}
