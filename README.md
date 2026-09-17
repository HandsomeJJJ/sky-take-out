# 苍穹外卖后端

基于 Spring Boot 的前后端分离外卖系统后端，覆盖用户下单、商家管理、订单处理与运营统计等核心场景。

## 主要功能

- 用户端：微信登录、菜品/套餐浏览、购物车、地址簿、下单、支付、订单查询与催单。
- 管理端：员工、分类、菜品、套餐、订单、营业状态与数据报表管理。
- 通用能力：JWT 鉴权、全局异常处理、文件上传、Redis 缓存、WebSocket 新订单提醒、订单定时处理。

## 技术栈

Spring Boot 2.7、MyBatis、MySQL、Redis、JWT、WebSocket、Kafka、RabbitMQ、Maven。

## 本次升级

| 能力 | 实现 | 价值 |
| --- | --- | --- |
| Redis 分布式锁 | 按用户提交订单加锁，UUID token + Lua 安全释放 | 防止重复提交订单 |
| Kafka 异步统计 | 订单事务提交后发布 `order.created`，消费者异步累加每日订单数 | 解耦统计链路、削峰 |
| RabbitMQ 延迟取消 | TTL 延迟队列 + 死信队列，15 分钟后条件取消未支付订单 | 减少定时扫描、保证幂等 |

消息处理遵循“事务提交后再投递”原则；Kafka 消费以订单 ID 去重，RabbitMQ 取消使用 `id + 待支付状态` 条件更新，避免重复消息产生副作用。

## 模块说明

```text
sky-common  公共常量、异常、工具与配置
sky-pojo    Entity、DTO、VO
sky-server  Controller、Service、Mapper、消息组件与启动类
```

## 本地运行

1. 配置 MySQL、Redis 以及 `application-dev.yml` 中的本地参数。
2. 启动 `SkyApplication`。
3. 如需启用升级的消息能力，启动 Kafka（`localhost:9092`）和 RabbitMQ（`localhost:5672`），并将：

```yaml
sky.messaging.kafka.enabled: true
sky.messaging.rabbitmq.enabled: true
```

默认均为 `false`，因此未部署消息中间件时不影响原有功能启动。

## Docker 启动

Docker Compose 会启动 MySQL、Redis、Kafka、RabbitMQ 与应用容器，并自动启用 Kafka/RabbitMQ 消息能力：

```bash
docker compose up --build -d
```

- 应用：`http://localhost:8080`
- RabbitMQ 管理台：`http://localhost:15672`（`guest` / `guest`）
- MySQL、Redis、Kafka 分别映射到本机 `3306`、`6379`、`9092` 端口。

首次启动只会创建空的 `sky_take_out` 数据库；请在启动应用前导入项目所需的表结构和初始化数据。停止并删除容器后，使用 `docker compose down`；如需连同容器数据一起删除，使用 `docker compose down -v`。
