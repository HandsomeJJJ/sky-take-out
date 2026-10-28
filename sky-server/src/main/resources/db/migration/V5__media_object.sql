-- =================================================================-----------
-- V5：对象存储的文件登记表（回收站）
--
-- 原来全项目只有 upload，没有任何删除：下架/换图之后旧图永久留在 bucket 里。
-- 但也不能"删菜品就删对象"——订单明细里存的是图片 URL 快照，历史订单还要看图，
-- 立刻删会把用户自己的订单详情变成一堆 404 图。
--
-- 所以走回收站：上传时登记 -> 不再被任何地方引用时标记待回收 -> 过保留期后物理删除。
-- 保留期覆盖"用户还会回看历史订单"的时间窗，到期才真的删。
-- ============================================================================

CREATE TABLE IF NOT EXISTS media_object
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    object_name VARCHAR(255) NOT NULL COMMENT '对象名（bucket 内的 key）',
    url         VARCHAR(512) NOT NULL COMMENT '完整访问地址，业务表里存的就是它',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0在用 1待回收 2已删除',
    retire_time DATETIME     DEFAULT NULL COMMENT '进入待回收的时间，物理删除以此为基准',
    create_time DATETIME     NOT NULL COMMENT '登记时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_media_object_name (object_name),
    -- 回收任务按"待回收 + 到期"扫描
    INDEX idx_media_status_retire (status, retire_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '对象存储文件登记表';

-- 回收前要确认没有订单明细还在引用这张图；order_detail 是行数最多的表之一，
-- 没有索引的话每次回收判断都是一次全表扫
CREATE INDEX idx_order_detail_image ON order_detail (image(64));
