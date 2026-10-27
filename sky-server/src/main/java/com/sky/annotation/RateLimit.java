package com.sky.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解：基于 Redis 计数器的固定窗口限流。
 * 用于下单、催单等易被刷的写接口。
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
