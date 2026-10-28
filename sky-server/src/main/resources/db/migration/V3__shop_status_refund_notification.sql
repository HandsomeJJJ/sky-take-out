-- =================================================================-----------
-- V3：营业状态、退款记录、订单通知，以及打包费的金额化
--
-- 这一批改的是"业务闭环缺口"，不是优化：
-- 1) 营业状态原来只存 Redis，缓存一丢就被当成营业中，打烊照样收单；
-- 2) 退款只有一次 HTTP 调用的日志，没有可重试、可对账的记录，也没有接收退款结果的端点；
-- 3) 管理端接单/拒单/派送/完成对用户静默发生；
-- 4) 打包费是 INT 且由客户端填，服务端算价之后这项口径要一起改成服务端计算 + 两位小数。
--
-- 全部 IF NOT EXISTS / 幂等 ALTER，已有库重复执行不会失败。
-- ============================================================================

-- 打包费改成金额（服务端按份数计算，可能出现小数）
ALTER TABLE orders
    MODIFY COLUMN pack_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '打包费（服务端计算）';

-- 店铺营业状态：单行表，状态是全局开关，必须落库
CREATE TABLE IF NOT EXISTS shop_status
(
    id          TINYINT      NOT NULL COMMENT '固定为 1，全局单值',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '0打烊 1营业',
    update_time DATETIME     NOT NULL COMMENT '最后修改时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '店铺营业状态';

INSERT IGNORE INTO shop_status (id, status, update_time) VALUES (1, 1, NOW());

-- 退款记录：申请 -> 微信受理 -> 回调确认的中间态必须可重试、可对账
CREATE TABLE IF NOT EXISTS order_refund
(
    id              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id        BIGINT         NOT NULL COMMENT '订单id',
    order_number    VARCHAR(50)    NOT NULL COMMENT '商户订单号',
    refund_no       VARCHAR(64)    NOT NULL COMMENT '商户退款单号，由订单号派生',
    amount          DECIMAL(10, 2) NOT NULL COMMENT '退款金额（元）',
    status          TINYINT        NOT NULL DEFAULT 0 COMMENT '0待发起 1已受理待回调 2成功 3失败待重试 4超限需人工',
    retry_count     INT            NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_time DATETIME       DEFAULT NULL COMMENT '下次重试时间，退避递增',
    wechat_refund_id VARCHAR(64)   DEFAULT NULL COMMENT '微信退款单号',
    last_error      VARCHAR(500)   DEFAULT NULL COMMENT '最后一次失败原因',
    reason          VARCHAR(255)   DEFAULT NULL COMMENT '退款原因',
    create_time     DATETIME       NOT NULL COMMENT '创建时间',
    update_time     DATETIME       NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 唯一键是"同一笔订单不会被退两次"的落点：并发/重投都撞在这里
    UNIQUE INDEX uk_refund_no (refund_no),
    INDEX idx_refund_status_next (status, next_retry_time),
    INDEX idx_refund_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单退款记录';

-- 订单通知：状态变更对用户留痕，替代"只能靠刷新订单列表发现"
CREATE TABLE IF NOT EXISTS order_notification
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT       NOT NULL COMMENT '接收通知的用户',
    order_id    BIGINT       DEFAULT NULL COMMENT '关联订单',
    type        TINYINT      NOT NULL COMMENT '1支付 2接单 3拒单 4派送 5完成 6取消 7退款',
    title       VARCHAR(50)  NOT NULL COMMENT '标题',
    content     VARCHAR(500) DEFAULT NULL COMMENT '正文',
    read_flag   TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_notification_user (user_id, read_flag, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单通知';
