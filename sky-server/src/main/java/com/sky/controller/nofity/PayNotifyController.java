package com.sky.controller.nofity;

import com.fasterxml.jackson.databind.JsonNode;
import com.sky.service.OrderService;
import com.sky.utils.JsonUtils;
import com.sky.utils.WeChatPayUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.entity.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支付回调相关接口
 */
@RestController
@RequestMapping("/notify")
@Slf4j
public class PayNotifyController {
    @Autowired
    private OrderService orderService;
    @Autowired
    private WeChatPayUtil weChatPayUtil;

    /**
     * 支付成功回调。只接受 POST —— 原实现用 @RequestMapping 不限方法，GET 也能打进来。
     */
    @PostMapping("/paySuccess")
    public void paySuccessNotify(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String body = readData(request);

        //读取请求头中的验签字段
        String serial = request.getHeader("Wechatpay-Serial");
        String timestamp = request.getHeader("Wechatpay-Timestamp");
        String nonce = request.getHeader("Wechatpay-Nonce");
        String signature = request.getHeader("Wechatpay-Signature");

        try {
            //验签 + 解密一体化：验签失败会抛异常，防止伪造"支付成功"回调
            String plainText = weChatPayUtil.verifyAndDecrypt(body, serial, timestamp, nonce, signature);
            JsonNode resource = JsonUtils.readTree(plainText);

            String outTradeNo = resource.path("out_trade_no").asText(null);//商户平台订单号
            String transactionId = resource.path("transaction_id").asText(null);//微信支付交易号
            String tradeState = resource.path("trade_state").asText(null);//交易状态
            JsonNode amount = resource.path("amount");
            Integer total = amount.has("total") ? amount.get("total").asInt() : null;//实付金额（分）

            log.info("支付回调验签通过，商户订单号：{}，微信交易号：{}，交易状态：{}，实付分：{}",
                    outTradeNo, transactionId, tradeState, total);

            if (outTradeNo == null) {
                throw new IllegalArgumentException("回调报文缺少 out_trade_no");
            }

            //业务处理，修改订单状态、来单提醒
            orderService.paySuccess(outTradeNo, tradeState, total);

            responseToWeixin(response, "SUCCESS", "成功");
        } catch (Exception e) {
            // 这里必须返回非成功应答：回 200 会让微信认为通知已送达并停止重试，
            // 订单就永久停在未支付状态。交由微信按其重试策略再次通知。
            log.error("支付回调处理失败，返回 FAIL 触发微信重试，报文长度={}", body == null ? 0 : body.length(), e);
            responseToWeixin(response, "FAIL", "处理失败");
        }
    }

    /**
     * 读取数据
     *
     * @param request
     * @return
     * @throws Exception
     */
    private String readData(HttpServletRequest request) throws IOException {
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
     * 给微信响应
     */
    private void responseToWeixin(HttpServletResponse response, String code, String message) throws IOException {
        // HTTP 200 且 code=SUCCESS 才表示通知成功；失败用 500 + FAIL，微信会重新推送
        response.setStatus("SUCCESS".equals(code) ? 200 : 500);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", message);
        response.setHeader("Content-type", ContentType.APPLICATION_JSON.toString());
        response.getOutputStream().write(JsonUtils.toJson(map).getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }
}
