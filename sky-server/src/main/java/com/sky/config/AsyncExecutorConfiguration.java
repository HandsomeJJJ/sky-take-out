package com.sky.config;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 业务侧异步执行器。
 * <p>
 * 不用默认线程池：`AsyncTaskExecutor` 的默认实现是无界队列 + 无上限并发，
 * 一旦下游卡住，任务会在内存里堆到 OOM，而且线程名看不出是谁的任务。
 * 这里显式给容量、线程名前缀与拒绝策略，并且把调用线程的 MDC 带过去，
 * 否则异步任务里的日志就没有 traceId，链路追踪在"提交到线程池"这一步断掉。
 */
@Configuration
@Slf4j
public class AsyncExecutorConfiguration {

    public static final String MQ_SEND_EXECUTOR = "mqSendExecutor";

    /**
     * 本地消息表的"立即发送"执行器。
     * <p>
     * 队列满时**直接拒绝**而不是 CallerRuns：这个任务丢了不影响正确性 ——
     * 消息本体已经在事务里写进 mq_message，补偿任务每 30 秒会把它捞起来重发。
     * 反过来如果为了"尽量发出去"而让调用线程兜底执行，下单响应就又会被
     * publisher confirm 的等待时间阻塞，等于白改。
     */
    @Bean(name = MQ_SEND_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor mqSendExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mq-send-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 优雅停机时等已提交的任务发完，避免关闭瞬间一批消息只落库没发送（还有补偿兜底，但没必要留窗口）
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }

    /**
     * 把提交线程的 MDC 复制到执行线程，执行完再还原。
     * 线程池会复用线程，不清理就会把上一个请求的 traceId 带到下一个任务里。
     */
    static class MdcTaskDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            Map<String, String> callerContext = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> workerContext = MDC.getCopyOfContextMap();
                if (callerContext != null) {
                    MDC.setContextMap(callerContext);
                } else {
                    MDC.clear();
                }
                try {
                    runnable.run();
                } finally {
                    if (workerContext != null) {
                        MDC.setContextMap(workerContext);
                    } else {
                        MDC.clear();
                    }
                }
            };
        }
    }
}
