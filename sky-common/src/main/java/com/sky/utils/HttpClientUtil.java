package com.sky.utils;

import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Http工具类
 */
public class HttpClientUtil {

    static final int TIMEOUT_MSEC = 5 * 1000;

    /**
     * 发送GET方式请求
     */
    public static String doGet(String url, Map<String, String> paramMap) throws IOException {
        URI uri;
        try {
            URIBuilder builder = new URIBuilder(url);
            if (paramMap != null) {
                for (Map.Entry<String, String> param : paramMap.entrySet()) {
                    builder.addParameter(param.getKey(), param.getValue());
                }
            }
            uri = builder.build();
        } catch (URISyntaxException e) {
            throw new IOException("非法请求地址：" + url, e);
        }

        return execute(new HttpGet(uri));
    }

    /**
     * 发送POST方式请求（表单）
     */
    public static String doPost(String url, Map<String, String> paramMap) throws IOException {
        HttpPost httpPost = new HttpPost(url);

        if (paramMap != null) {
            List<NameValuePair> paramList = new ArrayList<>();
            for (Map.Entry<String, String> param : paramMap.entrySet()) {
                paramList.add(new BasicNameValuePair(param.getKey(), param.getValue()));
            }
            // 模拟表单
            httpPost.setEntity(new UrlEncodedFormEntity(paramList, StandardCharsets.UTF_8));
        }

        httpPost.setConfig(builderRequestConfig());

        return execute(httpPost);
    }

    /**
     * 发送POST方式请求（JSON 请求体）
     */
    public static String doPost4Json(String url, Map<String, String> paramMap) throws IOException {
        HttpPost httpPost = new HttpPost(url);

        if (paramMap != null) {
            Map<String, String> body = new LinkedHashMap<>(paramMap);
            StringEntity entity = new StringEntity(JsonUtils.toJson(body), StandardCharsets.UTF_8);
            entity.setContentEncoding(StandardCharsets.UTF_8.name());
            entity.setContentType("application/json");
            httpPost.setEntity(entity);
        }

        httpPost.setConfig(builderRequestConfig());

        return execute(httpPost);
    }

    /**
     * 统一执行入口。原实现异常时 printStackTrace 并返回空串，上层拿着 "" 继续解析才抛 NPE，
     * 故障现场被推到离根因很远的地方；这里把异常直接抛给调用方。
     * 另外 doPost / doPost4Json 原先只关 response 不关 client，连接会泄漏。
     */
    private static String execute(HttpUriRequest request) throws IOException {
        try (CloseableHttpClient httpClient = HttpClients.createDefault();
             CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            String body = response.getEntity() == null
                    ? ""
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException("HTTP 请求失败，status=" + statusCode + "，url=" + request.getURI() + "，响应=" + body);
            }
            return body;
        }
    }

    private static RequestConfig builderRequestConfig() {
        return RequestConfig.custom()
                .setConnectTimeout(TIMEOUT_MSEC)
                .setConnectionRequestTimeout(TIMEOUT_MSEC)
                .setSocketTimeout(TIMEOUT_MSEC).build();
    }

}
