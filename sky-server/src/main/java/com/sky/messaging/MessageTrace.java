package com.sky.messaging;

import org.slf4j.MDC;

/**
 * 跨 MQ 的链路追踪透传。
 * <p>
 * traceId 原来只活在 Tomcat 线程的 MDC 里，消息一进 MQ 链路就断了：
 * 下单日志和处理这条下单消息的消费者日志对不上号。生产端把 traceId 写进消息头，
 * 消费端取出来放回 MDC，一次下单的完整链路才能用同一个 traceId 串起来。
 */
public final class MessageTrace {

    /** 消息头名，前后端与两个 MQ 共用 */
    public static final String HEADER = "sky-trace-id";

    /** MDC key，与 logback pattern 里的 %X{traceId} 保持一致 */
    public static final String MDC_KEY = "traceId";

    private MessageTrace() {
    }

    /** 当前线程的 traceId，可能为 null（定时任务、MQ 消费线程等没有 HTTP 入口） */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /**
     * 在消费线程上恢复链路上下文。返回值为本次写入的 traceId，配合 {@link #clear()} 使用。
     */
    public static String restore(String incoming) {
        String traceId = (incoming == null || incoming.isEmpty()) ? newTraceId() : incoming;
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    public static String newTraceId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
