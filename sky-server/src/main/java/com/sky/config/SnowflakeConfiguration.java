package com.sky.config;

import com.sky.utils.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

/**
 * 启动时把 workerId 注入雪花生成器。
 * 集群部署时通过环境变量 SNOWFLAKE_WORKER_ID 给每个实例下发不同编号
 * （或由注册中心/StatefulSet 序号派生），默认 1 只适用于单实例。
 */
@Configuration
@Slf4j
public class SnowflakeConfiguration {

    @Value("${sky.snowflake.worker-id:1}")
    private long workerId;

    @PostConstruct
    public void init() {
        SnowflakeIdGenerator.configure(workerId);
        log.info("雪花 ID 生成器 workerId={}", workerId);
    }
}
