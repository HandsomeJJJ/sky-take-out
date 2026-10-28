package com.sky.exception;

/**
 * 触发限流时抛出。
 * <p>
 * 携带"建议多久之后重试"，由全局异常处理器写成 HTTP 429 的 {@code Retry-After} 头 ——
 * 限流语义本来就该是"太多请求"，返回 200 + 业务错误码会让客户端把被限流的请求当成成功。
 */
public class RateLimitException extends BaseException {

    private final long retryAfterSeconds;

    public RateLimitException(String msg) {
        this(msg, 1);
    }

    public RateLimitException(String msg, long retryAfterSeconds) {
        super(msg);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
