package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地消息表：保证"业务成功则消息必达"。
 * 与业务数据写在同一个本地事务里，事务提交后异步发送，失败由补偿任务重发。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MqMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 待发送 */
    public static final int PENDING = 0;
    /** 已发送 */
    public static final int SENT = 1;
    /** 超过重试上限，死亡待人工处理 */
    public static final int DEAD = 2;
    /** 已被某个实例认领、正在发送 */
    public static final int SENDING = 3;

    private Long id;

    /** 业务类型，决定发到哪个 MQ：ORDER_TIMEOUT_CANCEL / ORDER_STATS */
    private String bizType;

    /** 业务 ID（订单号），用于幂等 */
    private String bizId;

    /** 消息体 JSON */
    private String payload;

    /** 状态：0 待发送 1 已发送 2 死亡 3 发送中 */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 下次重试时间 */
    private LocalDateTime nextRetryTime;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
