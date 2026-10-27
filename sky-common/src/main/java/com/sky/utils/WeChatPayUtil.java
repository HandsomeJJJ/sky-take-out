package com.sky.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.sky.properties.WeChatProperties;
import com.wechat.pay.contrib.apache.httpclient.WechatPayHttpClientBuilder;
import com.wechat.pay.contrib.apache.httpclient.auth.AutoUpdateCertificatesVerifier;
import com.wechat.pay.contrib.apache.httpclient.auth.PrivateKeySigner;
import com.wechat.pay.contrib.apache.httpclient.auth.Verifier;
import com.wechat.pay.contrib.apache.httpclient.auth.WechatPay2Credentials;
import com.wechat.pay.contrib.apache.httpclient.notification.Notification;
import com.wechat.pay.contrib.apache.httpclient.notification.NotificationHandler;
import com.wechat.pay.contrib.apache.httpclient.notification.NotificationRequest;
import com.wechat.pay.contrib.apache.httpclient.util.PemUtil;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信支付工具类
 */
@Component
public class WeChatPayUtil {

    //微信支付下单接口地址
    public static final String JSAPI = "https://api.mch.weixin.qq.com/v3/pay/transactions/jsapi";

    //申请退款接口地址
    public static final String REFUNDS = "https://api.mch.weixin.qq.com/v3/refund/domestic/refunds";

    @Autowired
    private WeChatProperties weChatProperties;

    /** 验签器，懒加载单例，内部会定时自动更新微信平台证书 */
    private volatile Verifier verifier;

    /**
     * 加载商户 API 私钥。
     */
    private PrivateKey loadPrivateKey() throws FileNotFoundException {
        return PemUtil.loadPrivateKey(new FileInputStream(new File(weChatProperties.getPrivateKeyFilePath())));
    }

    /**
     * 获取调用微信接口的客户端工具对象
     *
     * @return
     */
    private CloseableHttpClient getClient() {
        try {
            //merchantPrivateKey商户API私钥，如何加载商户API私钥请看常见问题
            PrivateKey merchantPrivateKey = loadPrivateKey();
            //加载平台证书文件
            X509Certificate x509Certificate = PemUtil.loadCertificate(new FileInputStream(new File(weChatProperties.getWeChatPayCertFilePath())));
            //wechatPayCertificates微信支付平台证书列表。你也可以使用后面章节提到的“定时更新平台证书功能”，而不需要关心平台证书的来龙去脉
            List<X509Certificate> wechatPayCertificates = Arrays.asList(x509Certificate);

            WechatPayHttpClientBuilder builder = WechatPayHttpClientBuilder.create()
                    .withMerchant(weChatProperties.getMchid(), weChatProperties.getMchSerialNo(), merchantPrivateKey)
                    .withWechatPay(wechatPayCertificates);

            // 通过WechatPayHttpClientBuilder构造的HttpClient，会自动的处理签名和验签
            CloseableHttpClient httpClient = builder.build();
            return httpClient;
        } catch (FileNotFoundException e) {
            // 原实现 printStackTrace 后返回 null，调用方直接 NPE 且看不出原因
            throw new IllegalStateException("微信支付客户端初始化失败，请检查商户私钥与平台证书路径配置", e);
        }
    }

    /**
     * 验签 + 解密一体化：校验微信支付回调的应答签名，并返回解密后的明文 resource。
     * 只有验签通过才会解密，任何人伪造请求都会在此抛异常被拦截。
     */
    public String verifyAndDecrypt(String body, String serial, String timestamp, String nonce, String signature) throws Exception {
        NotificationRequest request = new NotificationRequest.Builder()
                .withSerialNumber(serial)
                .withTimestamp(timestamp)
                .withNonce(nonce)
                .withSignature(signature)
                .withBody(body)
                .build();

        NotificationHandler handler = new NotificationHandler(getVerifier(),
                weChatProperties.getApiV3Key().getBytes(StandardCharsets.UTF_8));
        Notification notification = handler.parse(request);
        return notification.getDecryptData();
    }

    /**
     * 验签器（懒加载单例）。AutoUpdateCertificatesVerifier 会定时更新微信平台证书，
     * 避免平台证书轮换后验签失败。
     */
    private Verifier getVerifier() {
        if (verifier == null) {
            synchronized (this) {
                if (verifier == null) {
                    try {
                        WechatPay2Credentials credentials = new WechatPay2Credentials(
                                weChatProperties.getMchid(),
                                new PrivateKeySigner(weChatProperties.getMchSerialNo(), loadPrivateKey()));
                        verifier = new AutoUpdateCertificatesVerifier(
                                credentials, weChatProperties.getApiV3Key().getBytes(StandardCharsets.UTF_8));
                    } catch (FileNotFoundException e) {
                        throw new RuntimeException("加载商户私钥失败，无法初始化验签器", e);
                    }
                }
            }
        }
        return verifier;
    }

    /**
     * 发送post方式请求
     *
     * @param url
     * @param body
     * @return
     */
    private String post(String url, String body) throws Exception {
        CloseableHttpClient httpClient = getClient();

        HttpPost httpPost = new HttpPost(url);
        httpPost.addHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.toString());
        httpPost.addHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());
        httpPost.addHeader("Wechatpay-Serial", weChatProperties.getMchSerialNo());
        httpPost.setEntity(new StringEntity(body, "UTF-8"));

        CloseableHttpResponse response = httpClient.execute(httpPost);
        try {
            String bodyAsString = EntityUtils.toString(response.getEntity());
            return bodyAsString;
        } finally {
            httpClient.close();
            response.close();
        }
    }

    /**
     * 发送get方式请求
     *
     * @param url
     * @return
     */
    private String get(String url) throws Exception {
        CloseableHttpClient httpClient = getClient();

        HttpGet httpGet = new HttpGet(url);
        httpGet.addHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.toString());
        httpGet.addHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString());
        httpGet.addHeader("Wechatpay-Serial", weChatProperties.getMchSerialNo());

        CloseableHttpResponse response = httpClient.execute(httpGet);
        try {
            String bodyAsString = EntityUtils.toString(response.getEntity());
            return bodyAsString;
        } finally {
            httpClient.close();
            response.close();
        }
    }

    /**
     * jsapi下单
     *
     * @param orderNum    商户订单号
     * @param total       总金额
     * @param description 商品描述
     * @param openid      微信用户的openid
     * @return
     */
    private String jsapi(String orderNum, BigDecimal total, String description, String openid) throws Exception {
        Map<String, Object> jsonObject = new LinkedHashMap<>();
        jsonObject.put("appid", weChatProperties.getAppid());
        jsonObject.put("mchid", weChatProperties.getMchid());
        jsonObject.put("description", description);
        jsonObject.put("out_trade_no", orderNum);
        jsonObject.put("notify_url", weChatProperties.getNotifyUrl());

        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("total", toFen(total));
        amount.put("currency", "CNY");

        jsonObject.put("amount", amount);

        Map<String, Object> payer = new LinkedHashMap<>();
        payer.put("openid", openid);

        jsonObject.put("payer", payer);

        String body = JsonUtils.toJson(jsonObject);
        return post(JSAPI, body);
    }

    /**
     * 元转分。微信支付金额单位是分（整数）。
     * 原实现是 multiply(new BigDecimal(100)).setScale(2, ROUND_HALF_UP).intValue()：
     * setScale(2) 之后又 intValue() 截断，2.005 元这类金额会算错，改用 movePointRight + 精确取整。
     */
    public static int toFen(BigDecimal yuan) {
        if (yuan == null || yuan.signum() <= 0) {
            throw new IllegalArgumentException("微信支付金额必须大于 0，实际为：" + yuan);
        }
        return yuan.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /**
     * 小程序支付
     *
     * @param orderNum    商户订单号
     * @param total       金额，单位 元
     * @param description 商品描述
     * @param openid      微信用户的openid
     * @return
     */
    public Map<String, Object> pay(String orderNum, BigDecimal total, String description, String openid) throws Exception {
        //统一下单，生成预支付交易单
        String bodyAsString = jsapi(orderNum, total, description, openid);
        //解析返回结果
        JsonNode jsonObject = JsonUtils.readTree(bodyAsString);
        String prepayId = jsonObject.path("prepay_id").asText(null);
        if (prepayId != null) {
            String timeStamp = String.valueOf(System.currentTimeMillis() / 1000);
            String nonceStr = RandomStringUtils.randomNumeric(32);
            ArrayList<Object> list = new ArrayList<>();
            list.add(weChatProperties.getAppid());
            list.add(timeStamp);
            list.add(nonceStr);
            list.add("prepay_id=" + prepayId);
            //二次签名，调起支付需要重新签名
            StringBuilder stringBuilder = new StringBuilder();
            for (Object o : list) {
                stringBuilder.append(o).append("\n");
            }
            String signMessage = stringBuilder.toString();
            byte[] message = signMessage.getBytes();

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(PemUtil.loadPrivateKey(new FileInputStream(new File(weChatProperties.getPrivateKeyFilePath()))));
            signature.update(message);
            String packageSign = Base64.getEncoder().encodeToString(signature.sign());

            //构造数据给微信小程序，用于调起微信支付
            Map<String, Object> jo = new LinkedHashMap<>();
            jo.put("timeStamp", timeStamp);
            jo.put("nonceStr", nonceStr);
            jo.put("package", "prepay_id=" + prepayId);
            jo.put("signType", "RSA");
            jo.put("paySign", packageSign);

            return jo;
        }
        return JsonUtils.toObject(bodyAsString, Map.class);
    }

    /**
     * 申请退款
     *
     * @param outTradeNo    商户订单号
     * @param outRefundNo   商户退款单号
     * @param refund        退款金额
     * @param total         原订单金额
     * @return
     */
    public String refund(String outTradeNo, String outRefundNo, BigDecimal refund, BigDecimal total) throws Exception {
        Map<String, Object> jsonObject = new LinkedHashMap<>();
        jsonObject.put("out_trade_no", outTradeNo);
        jsonObject.put("out_refund_no", outRefundNo);

        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("refund", toFen(refund));
        amount.put("total", toFen(total));
        amount.put("currency", "CNY");

        jsonObject.put("amount", amount);
        jsonObject.put("notify_url", weChatProperties.getRefundNotifyUrl());

        String body = JsonUtils.toJson(jsonObject);

        //调用申请退款接口
        return post(REFUNDS, body);
    }
}
