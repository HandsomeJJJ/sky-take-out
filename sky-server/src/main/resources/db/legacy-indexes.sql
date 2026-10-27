-- ============================================================================
-- 存量数据库补索引/唯一约束（需人工执行，不在 Flyway 里跑）
--
-- V1 用的是 CREATE TABLE IF NOT EXISTS，对已有库是空操作，新增的唯一索引与
-- 组合索引不会自动附加到已存在的表上。本地新库不需要执行本文件。
--
-- 执行前务必先查重复值：唯一索引在存在重复数据时会创建失败。
-- ============================================================================

-- 1) 建索引前先确认没有重复，有的话先人工清理
SELECT 'orders.number 重复' AS check_item, number, COUNT(*) AS cnt
FROM orders GROUP BY number HAVING cnt > 1;

SELECT 'employee.username 重复' AS check_item, username, COUNT(*) AS cnt
FROM employee GROUP BY username HAVING cnt > 1;

SELECT 'user.openid 重复' AS check_item, openid, COUNT(*) AS cnt
FROM `user` GROUP BY openid HAVING cnt > 1;

SELECT 'mq_message(biz_type,biz_id) 重复' AS check_item, biz_type, biz_id, COUNT(*) AS cnt
FROM mq_message GROUP BY biz_type, biz_id HAVING cnt > 1;

SELECT 'shopping_cart 重复行' AS check_item, user_id, dish_id, setmeal_id, dish_flavor, COUNT(*) AS cnt
FROM shopping_cart GROUP BY user_id, dish_id, setmeal_id, dish_flavor HAVING cnt > 1;

-- 2) BCrypt 摘要是 60 字符，旧的 varchar(32) 列必须放宽，否则写入被截断
ALTER TABLE employee MODIFY COLUMN password VARCHAR(100) NOT NULL COMMENT '登录密码(BCrypt)';

-- 3) 唯一约束
ALTER TABLE orders ADD UNIQUE INDEX uk_orders_number (number);
ALTER TABLE employee ADD UNIQUE INDEX uk_employee_username (username);
ALTER TABLE `user` ADD UNIQUE INDEX uk_user_openid (openid);
ALTER TABLE mq_message ADD UNIQUE INDEX uk_mq_message_biz (biz_type, biz_id);
-- 已有库里"发送中"这个状态值是注释里的，不是枚举类型，无需变更列定义
-- 购物车唯一键要走生成列：直接建在 dish_id/setmeal_id 上会被 NULL 绕过
ALTER TABLE shopping_cart
    ADD COLUMN item_key VARCHAR(64) GENERATED ALWAYS AS (CONCAT(COALESCE(dish_id, 0), '-',
        COALESCE(setmeal_id, 0), '-', COALESCE(dish_flavor, ''))) STORED COMMENT '商品唯一键（NULL 归一后的生成列）';
ALTER TABLE shopping_cart ADD UNIQUE INDEX uk_cart_user_item (user_id, item_key);

-- 4) 查询索引
ALTER TABLE orders ADD INDEX idx_orders_user_time (user_id, order_time);
ALTER TABLE orders ADD INDEX idx_orders_status_time (status, order_time);
ALTER TABLE order_detail ADD INDEX idx_order_detail_order (order_id);
ALTER TABLE address_book ADD INDEX idx_address_book_user (user_id);
ALTER TABLE dish ADD INDEX idx_dish_status (status);
ALTER TABLE setmeal ADD INDEX idx_setmeal_status (status);
