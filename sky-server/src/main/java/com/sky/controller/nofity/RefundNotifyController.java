package com.sky.controller.nofity;

import com.fasterxml.jackson.databind.JsonNode;
import com.sky.service.OrderRefundService;
import com.sky.utils.JsonUtils;
import com.sky.utils.WeChatPayUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 微信退款结果回调。
 * <p>
 * 之前配置里有 refundNotifyUrl，但没有对应端点：退款是否真的到账只剩本地那条记录，
 * 微信侧的异步结果（受理 -> 成功/失败）没有任何地方接收，也就无法纠正。
 */
@RestController
@RequestMapping("/notify")
@Slf4j
public class RefundNotifyController {

    @Autowired
    private OrderRefundService orderRefundService;
    @Autowired
    private WeChatPayUtil weChatPayUtil;

    /**
     * 与支付回调同样处理：先验签再解密，处理失败返回非 200 触发微信重试。
     */
    @PostMapping("/refundSuccess")
    public void refundNotify(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String body = NotifyBodyReader.read(request);
        String serial = request.getHeader("Wechatpay-Serial");
        String timestamp = request.getHeader("Wechatpay-Timestamp");
        String nonce = request.getHeader("Wechatpay-Nonce");
        String signature = request.getHeader("Wechatpay-Signature");

        try {
            String plainText = weChatPayUtil.verifyAndDecrypt(body, serial, timestamp, nonce, signature);
            JsonNode resource = JsonUtils.readTree(plainText);

            String outRefundNo = resource.path("out_refund_no").asText(null);
            String refundStatus = resource.path("refund_status").asText(null);
            String wechatRefundId = resource.path("refund_id").asText(null);
            JsonNode amount = resource.path("amount");
            Integer refundFen = amount.has("refund") ? amount.get("refund").asInt() : null;

            if (outRefundNo == null) {
                throw new IllegalArgumentException("退款回调报文缺少 out_refund_no");
            }
            log.info("退款回调验签通过，商户退款单号：{}，状态：{}，退款金额：{}分",
                    outRefundNo, refundStatus, refundFen);

            orderRefundService.handleRefundNotify(outRefundNo, refundStatus, refundFen, wechatRefundId);
            NotifyBodyReader.respond(response, true, null);
        } catch (Exception e) {
            log.error("退款回调处理失败，返回 FAIL 触发微信重试，报文长度={}", body == null ? 0 : body.length(), e);
            NotifyBodyReader.respond(response, false, "处理失败");
        }
    }
}
