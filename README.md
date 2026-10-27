# 苍穹外卖后端

基于 Spring Boot 的前后端分离外卖系统后端，覆盖用户下单、商家管理、订单处理与运营统计等核心场景。

## 主要功能

- 用户端：微信登录、菜品/套餐浏览、购物车、地址簿、下单、支付、订单查询与催单。
- 管理端：员工、分类、菜品、套餐、订单、营业状态与数据报表管理。
- 通用能力：JWT 鉴权与登出失效、参数校验、全局异常、文件上传校验、Redis 缓存、WebSocket 新订单提醒（跨实例广播）、订单超时取消。

## 技术栈

Spring Boot 2.7、Java 17、MyBatis、MySQL 8、Redis（缓存 + Pub/Sub + Redisson 锁）、Kafka、RabbitMQ、Flyway、WebSocket、JWT(jjwt 0.12)、BCrypt、POI 5、Maven、Docker Compose、GitHub Actions。

序列化统一用 Jackson（`JsonUtils`），项目不再依赖 fastjson。

## 关键设计

| 能力 | 实现 |
| --- | --- |
| 下单金额 | 只由服务端按购物车明细计算，请求参数里没有金额字段 |
| 越权防护 | 订单、地址簿、购物车的读写一律按当前登录用户 id 过滤 |
| 订单状态推进 | 全部走"带前置状态的条件更新"（乐观锁），影响行数为 0 即状态冲突 |
| 支付回调 | 验签 + 解密 → 校验 `trade_state` 与实付金额 → `where pay_status=0` 条件更新 → 才推送来单提醒 |
| MQ 可靠送达 | 本地消息表 `mq_message` 与业务同事务写入；先认领再发送，拿到 broker 确认才标已发送；失败指数退避，超限进死亡态 |
| 消费可靠性 | Rabbit 手动 ack，坏消息进死信队列；Kafka 显式建 topic、`acks=all` + 幂等生产者 |
| 链路追踪 | traceId 进日志 MDC，并通过消息头 `sky-trace-id` 穿透到消费者线程 |
| 缓存一致性 | Spring Cache + `TransactionAwareCacheManagerProxy`，删缓存延到事务提交之后，统一 60 分钟 TTL |
| 数据库版本 | Flyway 管理 schema 与索引；存量库补索引见 `sky-server/src/main/resources/db/legacy-indexes.sql` |

## 本地运行

1. 准备 MySQL 8 与 Redis；`application-dev.yml` 里的数据源/Redis 参数按需调整。
2. 注入 JWT 密钥（长度至少 32 字节，缺失会启动失败）：

```bash
export JWT_ADMIN_SECRET_KEY=$(openssl rand -base64 48 | tr -d '\n=')
export JWT_USER_SECRET_KEY=$(openssl rand -base64 48 | tr -d '\n=')
```

3. 启动 `SkyApplication`。首次启动 Flyway 会自动建表并写入初始管理员 `admin` / `123456`（演示账号，请尽快改密）。
4. 如需启用消息能力，启动 Kafka（`localhost:9092`）和 RabbitMQ（`localhost:5672`），并把
   `sky.messaging.kafka.enabled`、`sky.messaging.rabbitmq.enabled` 改为 `true`（默认 `false`，不起 MQ 也能正常跑）。

> dev profile 已内置两个仅本地使用的占位密钥，不想设环境变量也能直接启动；生产 profile 不提供默认值。

## Docker 启动

```bash
cp .env.example .env      # 至少填两个 JWT 密钥，否则 compose 拒绝启动
docker compose up --build -d
```

- 应用：`http://localhost:8080`
- RabbitMQ 管理台：`http://127.0.0.1:15672`
- MySQL / Redis / Kafka / RabbitMQ 端口只绑定 `127.0.0.1`，不暴露到局域网。

表结构由 Flyway 在应用启动时创建，不再需要手工导入。

## 模块说明

```text
sky-common  公共常量、异常、工具（JsonUtils / PasswordHasher / JwtUtil）与配置属性
sky-pojo    Entity、DTO（含校验注解）、VO
sky-server  Controller、Service、Mapper、消息组件、拦截器与安全组件
```

## 测试与 CI

```bash
mvn test          # 单元测试：下单算价、越权、支付回调幂等、报表日期守卫、密码哈希、元转分
```

CI（`.github/workflows/ci.yml`）会额外断言编译产物是 Java 17 字节码（class file major version = 61），
防止 `pom.xml` 漏写 `java.version` 后静默退回 Java 8。

## 已知边界

- 微信支付走真实商户接口，本仓库未在沙箱完成端到端联调；回调验签与金额校验的逻辑有单测覆盖。
- 营业额统计结果仍是 `Double`，未打通全链路 `BigDecimal`。
- `WeChatPayUtil` / `HttpClientUtil` 每次请求新建 HTTP client，没有连接池与熔断。
- 接口文档（Swagger/knife4j）只在非 docker profile 下启用。

## 相关文档

- `项目升级完善清单.md`：分级待办与每一批改造的落地方式、行为变更清单。
- `Java全栈面试准备.md`：按"面试官会怎么追问"组织的项目实现细节与问答库。
