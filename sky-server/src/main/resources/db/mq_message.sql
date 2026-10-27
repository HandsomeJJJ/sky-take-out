-- 本地消息表：保证"业务成功则消息必达"
-- 与业务数据写在同一个本地事务里，事务提交后异步发送，失败由补偿任务重发。
CREATE TABLE IF NOT EXISTS mq_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    biz_type        VARCHAR(64)  NOT NULL COMMENT '业务类型：ORDER_TIMEOUT_CANCEL / ORDER_STATS',
    biz_id          VARCHAR(64)  NOT NULL COMMENT '业务ID（订单号），用于幂等',
    payload         TEXT         NOT NULL COMMENT '消息体 JSON',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0待发送 1已发送 2死亡',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_time DATETIME     NOT NULL COMMENT '下次重试时间',
    create_time     DATETIME     NOT NULL COMMENT '创建时间',
    update_time     DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_status_retry (status, next_retry_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本地消息表';
