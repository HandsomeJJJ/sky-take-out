package com.sky.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解：基于 Redis ZSET 的滑动窗口计数。
 * 用于下单、催单等易被刷的写接口；被限流时由全局异常处理器返回 HTTP 429 + Retry-After。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 时间窗口内允许的最大请求次数 */
    int limit() default 10;

    /** 时间窗口，单位：秒 */
    int window() default 1;

    /** 限流 key 前缀，默认取方法名 */
    String key() default "";
}
