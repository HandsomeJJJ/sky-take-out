package com.sky.exception;

/**
 * 触发限流时抛出，由全局异常处理器统一返回友好提示。
 */
public class RateLimitException extends BaseException {

    public RateLimitException(String msg) {
        super(msg);
    }
}
