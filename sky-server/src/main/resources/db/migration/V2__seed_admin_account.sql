-- ============================================================================
-- 初始管理员账号，让 docker compose 起来之后管理端可以直接登录。
--
-- 密码列存的是 BCrypt 摘要（对应明文 123456），这是演示账号，
-- 任何非本地环境都必须先改密或删除本行。
-- INSERT IGNORE + username 唯一索引：重复执行不会产生第二个 admin。
-- ============================================================================

INSERT IGNORE INTO employee (username, name, password, phone, sex, id_number, status,
                             create_time, update_time, create_user, update_user)
VALUES ('admin',
        '管理员',
        '$2a$10$MwZvTWcBHworr18G0bch3.qvbldb070YIY6pk4zNWl6eynXLINXa2',
        '13800000000',
        '1',
        '110101199001010000',
        1,
        NOW(), NOW(), 1, 1);
