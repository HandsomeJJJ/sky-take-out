-- ============================================================================
-- 苍穹外卖 基线建表脚本
--
-- 之前仓库里没有 schema DDL：docker compose 只能创建一个空库，README 让人
-- "自行导入表结构"，等于项目不可复现。这份脚本按实体与 Mapper 的列名逐一对齐。
--
-- 全部使用 CREATE TABLE IF NOT EXISTS：已有数据的库首次接入 Flyway 时整脚本是
-- 空操作，不会动现有数据。给存量库补索引请手工执行 db/legacy-indexes.sql。
-- ============================================================================

-- 员工（管理端账号）
-- password 给到 varchar(100)：BCrypt 摘要固定 60 字符，
-- 若沿用常见的 varchar(32) 会被静默截断成一段永远校验不过的坏哈希。
CREATE TABLE IF NOT EXISTS employee
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username    VARCHAR(32)  NOT NULL COMMENT '登录账号',
    name        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '姓名',
    password    VARCHAR(100) NOT NULL COMMENT '登录密码(BCrypt)',
    phone       VARCHAR(11)  DEFAULT NULL COMMENT '手机号',
    sex         VARCHAR(2)   DEFAULT NULL COMMENT '性别 0女 1男',
    id_number   VARCHAR(18)  DEFAULT NULL COMMENT '身份证号',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0锁定 1启用',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
    create_user BIGINT       DEFAULT NULL COMMENT '创建人id',
    update_user BIGINT       DEFAULT NULL COMMENT '修改人id',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_employee_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '员工';

-- 菜品/套餐分类
CREATE TABLE IF NOT EXISTS category
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    type        TINYINT     NOT NULL COMMENT '类型 1菜品分类 2套餐分类',
    name        VARCHAR(32) NOT NULL COMMENT '分类名称',
    sort        INT         NOT NULL DEFAULT 0 COMMENT '排序',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态 0禁用 1启用',
    create_time DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    create_user BIGINT      DEFAULT NULL COMMENT '创建人id',
    update_user BIGINT      DEFAULT NULL COMMENT '修改人id',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_category_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '菜品及套餐分类';

-- 菜品
CREATE TABLE IF NOT EXISTS dish
(
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(32)   NOT NULL COMMENT '菜品名称',
    category_id BIGINT        NOT NULL COMMENT '分类id',
    price       DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '菜品价格',
    image       VARCHAR(255)  DEFAULT NULL COMMENT '图片',
    description VARCHAR(255)  DEFAULT NULL COMMENT '描述信息',
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '状态 0停售 1起售',
    create_time DATETIME      DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME      DEFAULT NULL COMMENT '更新时间',
    create_user BIGINT        DEFAULT NULL COMMENT '创建人id',
    update_user BIGINT        DEFAULT NULL COMMENT '修改人id',
    PRIMARY KEY (id),
    INDEX idx_dish_category (category_id),
    INDEX idx_dish_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '菜品';

-- 菜品口味
CREATE TABLE IF NOT EXISTS dish_flavor
(
    id      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    dish_id BIGINT       NOT NULL COMMENT '菜品id',
    name    VARCHAR(32)  NOT NULL COMMENT '口味名称',
    value   VARCHAR(128) NOT NULL COMMENT '口味数据',
    PRIMARY KEY (id),
    INDEX idx_dish_flavor_dish (dish_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '菜品口味';

-- 套餐
CREATE TABLE IF NOT EXISTS setmeal
(
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    category_id BIGINT        NOT NULL COMMENT '分类id',
    name        VARCHAR(64)   NOT NULL COMMENT '套餐名称',
    price       DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '套餐价格',
    status      TINYINT       NOT NULL DEFAULT 1 COMMENT '状态 0停售 1起售',
    description VARCHAR(255)  DEFAULT NULL COMMENT '描述信息',
    image       VARCHAR(255)  DEFAULT NULL COMMENT '图片',
    create_time DATETIME      DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME      DEFAULT NULL COMMENT '更新时间',
    create_user BIGINT        DEFAULT NULL COMMENT '创建人id',
    update_user BIGINT        DEFAULT NULL COMMENT '修改人id',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_setmeal_name (name),
    INDEX idx_setmeal_category (category_id),
    INDEX idx_setmeal_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '套餐';

-- 套餐与菜品的关系
CREATE TABLE IF NOT EXISTS setmeal_dish
(
    id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    setmeal_id BIGINT        NOT NULL COMMENT '套餐id',
    dish_id    BIGINT        NOT NULL COMMENT '菜品id',
    name       VARCHAR(32)   DEFAULT NULL COMMENT '菜品名称(冗余)',
    price      DECIMAL(10, 2) DEFAULT NULL COMMENT '菜品单价(冗余)',
    copies     INT           NOT NULL DEFAULT 1 COMMENT '菜品份数',
    PRIMARY KEY (id),
    INDEX idx_setmeal_dish_setmeal (setmeal_id),
    INDEX idx_setmeal_dish_dish (dish_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '套餐菜品关系';

-- C端微信用户
CREATE TABLE IF NOT EXISTS `user`
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    openid      VARCHAR(50)  NOT NULL COMMENT '微信openid',
    name        VARCHAR(32)  DEFAULT NULL COMMENT '昵称',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    sex         VARCHAR(2)   DEFAULT NULL COMMENT '性别',
    id_number   VARCHAR(18)  DEFAULT NULL COMMENT '身份证号',
    avatar      VARCHAR(255) DEFAULT NULL COMMENT '头像',
    create_time DATETIME     DEFAULT NULL COMMENT '注册时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_user_openid (openid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '微信用户';

-- 收货地址
CREATE TABLE IF NOT EXISTS address_book
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id       BIGINT       NOT NULL COMMENT '用户id',
    consignee     VARCHAR(50)  NOT NULL COMMENT '收货人',
    phone         VARCHAR(20)  NOT NULL COMMENT '手机号',
    sex           VARCHAR(2)   DEFAULT NULL COMMENT '性别',
    province_code VARCHAR(12)  DEFAULT NULL COMMENT '省级行政区划编码',
    province_name VARCHAR(50)  DEFAULT NULL COMMENT '省',
    city_code     VARCHAR(12)  DEFAULT NULL COMMENT '城市编码',
    city_name     VARCHAR(50)  DEFAULT NULL COMMENT '城市',
    district_code VARCHAR(12)  DEFAULT NULL COMMENT '县区编码',
    district_name VARCHAR(50)  DEFAULT NULL COMMENT '县区',
    detail        VARCHAR(200) NOT NULL COMMENT '详细地址',
    label         VARCHAR(100) DEFAULT NULL COMMENT '标签 家/公司/学校',
    is_default    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认 0否 1是',
    PRIMARY KEY (id),
    INDEX idx_address_book_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '收货地址';

-- 购物车
-- 注意：dish_id 与 setmeal_id 每行必有一个是 NULL，口味也常为 NULL，
-- 而 MySQL 的唯一索引把 NULL 视为互不相等 —— 直接建在原始列上等于没有约束。
-- 因此用 STORED 生成列把三列压成一个非空字符串，再对 (user_id, item_key) 建唯一键。
CREATE TABLE IF NOT EXISTS shopping_cart
(
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(32)   DEFAULT NULL COMMENT '商品名称',
    image       VARCHAR(255)  DEFAULT NULL COMMENT '图片',
    user_id     BIGINT        NOT NULL COMMENT '用户id',
    dish_id     BIGINT        DEFAULT NULL COMMENT '菜品id',
    setmeal_id  BIGINT        DEFAULT NULL COMMENT '套餐id',
    dish_flavor VARCHAR(50)   DEFAULT NULL COMMENT '口味',
    number      INT           NOT NULL DEFAULT 1 COMMENT '商品数量',
    amount      DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '商品单价',
    create_time DATETIME      DEFAULT NULL COMMENT '加入时间',
    item_key    VARCHAR(64)   GENERATED ALWAYS AS (CONCAT(COALESCE(dish_id, 0), '-',
                COALESCE(setmeal_id, 0), '-', COALESCE(dish_flavor, ''))) STORED
                COMMENT '商品唯一键（NULL 归一后的生成列）',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_cart_user_item (user_id, item_key),
    INDEX idx_cart_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '购物车';

-- 订单
-- number 上加唯一索引：雪花算法只是降低撞号概率，数据库约束才是最后防线。
CREATE TABLE IF NOT EXISTS orders
(
    id                        BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    number                    VARCHAR(50)   NOT NULL COMMENT '订单号',
    status                    TINYINT       NOT NULL DEFAULT 1 COMMENT '1待付款 2待接单 3已接单 4派送中 5已完成 6已取消',
    user_id                   BIGINT        NOT NULL COMMENT '下单用户id',
    address_book_id           BIGINT        DEFAULT NULL COMMENT '地址id',
    order_time                DATETIME      NOT NULL COMMENT '下单时间',
    checkout_time             DATETIME      DEFAULT NULL COMMENT '结账时间',
    pay_method                TINYINT       NOT NULL DEFAULT 1 COMMENT '1微信 2支付宝',
    pay_status                TINYINT       NOT NULL DEFAULT 0 COMMENT '0未支付 1已支付 2退款',
    amount                    DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '实收金额',
    remark                    VARCHAR(255)  DEFAULT NULL COMMENT '备注',
    phone                     VARCHAR(20)   DEFAULT NULL COMMENT '手机号',
    address                   VARCHAR(255)  DEFAULT NULL COMMENT '地址',
    consignee                 VARCHAR(50)   DEFAULT NULL COMMENT '收货人',
    cancel_reason             VARCHAR(255)  DEFAULT NULL COMMENT '订单取消原因',
    rejection_reason          VARCHAR(255)  DEFAULT NULL COMMENT '订单拒绝原因',
    cancel_time               DATETIME      DEFAULT NULL COMMENT '订单取消时间',
    estimated_delivery_time   DATETIME      DEFAULT NULL COMMENT '预计送达时间',
    delivery_status           TINYINT       DEFAULT 1 COMMENT '配送状态 1立即送出 0选择具体时间',
    delivery_time             DATETIME      DEFAULT NULL COMMENT '送达时间',
    pack_amount               INT           NOT NULL DEFAULT 0 COMMENT '打包费',
    tableware_number          INT           NOT NULL DEFAULT 0 COMMENT '餐具数量',
    tableware_status          TINYINT       NOT NULL DEFAULT 1 COMMENT '餐具数量状态 1按餐量提供 0选择具体数量',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_orders_number (number),
    INDEX idx_orders_user_time (user_id, order_time),
    -- 超时扫描 / 状态统计 / 报表都是"状态 + 时间"的过滤，没有这个索引就是全表扫
    INDEX idx_orders_status_time (status, order_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单表';

-- 订单明细
CREATE TABLE IF NOT EXISTS order_detail
(
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(64)   DEFAULT NULL COMMENT '商品名称',
    image       VARCHAR(255)  DEFAULT NULL COMMENT '图片',
    order_id    BIGINT        NOT NULL COMMENT '订单id',
    dish_id     BIGINT        DEFAULT NULL COMMENT '菜品id',
    setmeal_id  BIGINT        DEFAULT NULL COMMENT '套餐id',
    dish_flavor VARCHAR(50)   DEFAULT NULL COMMENT '口味',
    number      INT           NOT NULL DEFAULT 1 COMMENT '数量',
    amount      DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '单价',
    PRIMARY KEY (id),
    INDEX idx_order_detail_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '订单明细';

-- 本地消息表：保证"业务成功则消息必达"
CREATE TABLE IF NOT EXISTS mq_message
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    biz_type        VARCHAR(64) NOT NULL COMMENT '业务类型：ORDER_TIMEOUT_CANCEL / ORDER_STATS',
    biz_id          VARCHAR(64) NOT NULL COMMENT '业务ID（订单号），用于幂等',
    payload         TEXT        NOT NULL COMMENT '消息体 JSON',
    status          TINYINT     NOT NULL DEFAULT 0 COMMENT '0待发送 1已发送 2死亡 3发送中',
    retry_count     INT         NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_time DATETIME    NOT NULL COMMENT '下次重试时间',
    create_time     DATETIME    NOT NULL COMMENT '创建时间',
    update_time     DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 同一个业务事件只允许登记一次，重复下单/重复投递在库里就被挡住
    UNIQUE INDEX uk_mq_message_biz (biz_type, biz_id),
    INDEX idx_mq_status_retry (status, next_retry_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本地消息表';
