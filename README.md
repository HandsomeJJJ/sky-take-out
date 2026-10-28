# 苍穹外卖后端

基于 Spring Boot 的前后端分离外卖系统后端，覆盖用户下单、商家管理、订单处理与运营统计等核心场景。

## 主要功能

- 用户端：微信登录、菜品/套餐浏览、购物车、地址簿、下单、支付、订单查询与催单、订单通知（未读数/拉取/标记已读）。
- 管理端：员工、分类、菜品、套餐、订单（接单/拒单/派送/完成/取消）、营业状态、数据报表与退款记录。
- 通用能力：JWT 鉴权与登出/禁用即时失效、参数校验、全局异常、滑动窗口限流与登录防爆破、文件上传校验与对象回收、Redis 缓存（抖动 TTL + 互斥回源）、WebSocket 新订单提醒（握手鉴权 + 跨实例广播）、订单超时取消、派送超时收尾、退款申请-回调-补偿闭环。

## 技术栈

Spring Boot 2.7、Java 17、MyBatis、MySQL 8、Redis（缓存 + Pub/Sub + Redisson 锁）、Kafka、RabbitMQ、Flyway、WebSocket、JWT(jjwt 0.12)、BCrypt、POI 5、Maven、Docker Compose、GitHub Actions。

序列化统一用 Jackson（`JsonUtils`），项目不再依赖 fastjson。

## 关键设计

| 能力 | 实现 |
| --- | --- |
| 下单金额 | 只由服务端按购物车明细计算，请求参数里没有金额字段（打包费同样由服务端按份数算并封顶） |
| 下单前复核 | 按 id 批量回查商品表，校验**当前在售**并用**当前价格**重算；任一行已下架/已删除就整单拒绝并点名商品 |
| 营业状态 | 单行表 `shop_status` 为准 + Redis 10 分钟缓存，读不到回源 DB；打烊时加购与下单都被业务异常拦住 |
| 越权防护 | 订单、地址簿、购物车、通知的读写一律按当前登录用户 id 过滤 |
| 订单状态推进 | 全部走"带前置状态的条件更新"（乐观锁），影响行数为 0 即状态冲突 |
| 支付回调 | 验签 + 解密 → 校验 `trade_state` 与实付金额 → `where pay_status=0 and status=1` 条件更新 → 才推送提醒；条件更新落空且订单已取消则判定迟到支付并**自动全额退款** |
| 退款闭环 | `order_refund` 表 + 状态机，`refund_no = "RF"+订单号` 唯一索引防二次退款；`/notify/refundSuccess` 接收结果并核金额；`RefundCompensator` 带锁退避重投，8 次转人工 |
| 订单通知 | 接单/拒单/派送/完成/取消/超时/退款都写 `order_notification`，用户端三个接口拉取（长连接未与用户绑定，定向触达走站内消息） |
| 令牌失效 | 登出用 SHA-256 黑名单；禁用账号按员工记"作废时间点"并比对令牌 `iat`；两处增强校验在 Redis 故障时 fail-open |
| 防爆破 | `@RateLimit` 未登录按客户端 IP 分桶，罩住两个登录接口；账号维度失败计数 5 次锁 15 分钟（账号不存在不计数） |
| MQ 可靠送达 | 本地消息表 `mq_message` 与业务同事务写入；先认领再发送，拿到 broker 确认才标已发送；失败指数退避，超限进死亡态 |
| 发送路径 | 事务提交后**异步**投递（独立线程池，饱和就交给补偿任务），发送锁按 Rabbit / Kafka 两个通道拆开，互不阻塞 |
| 消费可靠性 | Rabbit 手动 ack，坏消息进死信队列，**死信有消费者**：能识别的订单补做超时取消并通知用户，无论成败都 ack；Kafka 显式建 topic、`acks=all` + 幂等生产者，异常一律交回 `DefaultErrorHandler` 退避重试、用尽落 `order.created.DLT`，失败时先撤销去重标记 |
| 超时兜底 | 延迟队列 + `OrderTimeoutFallbackTask` **双轨并存**（不再"MQ 开着就不扫"），取消走 `where status=1` 的条件更新，重复扫描影响 0 行 |
| 链路追踪 | traceId 进日志 MDC，并通过消息头 `sky-trace-id` 穿透到消费者线程（异步发送与线程池也带 MDC） |
| 缓存一致性 | Spring Cache + `TransactionAwareCacheManagerProxy`，删缓存延到事务提交之后；TTL 每个 key ±20% 随机抖动（防集中过期），读接口 `@Cacheable(sync=true)` + 互斥写器（防击穿），空值也缓存（防穿透） |
| 限流 | Redis ZSET **滑动窗口**，清理/计数/写入/续期在一段 Lua 里原子完成；超限返回 **HTTP 429 + `Retry-After`**；维度为登录用户 `uid:` / 未登录客户端 `ip:` |
| 报表口径 | 时间区间一律半开 `[begin 00:00, end+1 00:00)`（整点与跨日边界不再漏行）；逐日循环查询换成两条 `group by date(...)` 聚合，总用户数取起点存量 + 每日新增；销量 Top10 按 SKU id 分组 |
| 派送超时 | 以 `delivery_start_time`（接单后点派送时写入）为判定基准，阈值 `sky.order.auto-complete-after-minutes` 可配；历史数据该列为空时回退按下单时间 |
| 个人信息 | 员工列表 SQL 不取 `password` / `id_number`；实体与 DTO 的敏感字段 `@ToString.Exclude`，控制器日志只留 id 与用户名；SQL 日志只在 dev 开 |
| 文件生命周期 | 上传即登记 `media_object`；商品不再引用时先查菜品/套餐/购物车/订单明细四张表的引用计数，都无引用才进待回收，再过保留期（默认 180 天）才真正调 OSS 删除；回收前二次确认、条件更新保证多实例只删一次 |
| WebSocket | 管理端来单/催单广播，跨实例用 Redis Pub/Sub；**握手必须带管理端 JWT**（`ws://host/ws/{sid}?token=...`，要求验签通过且含 `empId`），鉴权器未注册时 fail-closed |
| 数据库版本 | Flyway 管理 schema 与索引（V1 建表、V2 初始账号、V3 营业状态/退款/通知、V4 派送开始时间、V5 对象登记）；存量库补索引见 `sky-server/src/main/resources/db/legacy-indexes.sql` |

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
mvn test          # 123 条断言型用例：下单算价与复核、打烊/下架拦截、越权、支付回调幂等与迟到支付、
                  # 退款幂等与补偿、状态变更通知、员工禁用即时失效、登录失败锁定、报表日期守卫与聚合口径、
                  # 缓存 TTL 抖动、滑动窗口限流与 429、死信补做与必 ack、Kafka 失败不吞消息、
                  # 图片回收的引用计数、WebSocket 握手鉴权、菜品口味批量查询、
                  # BCrypt、元转分、JSON 往返精度
```

> 本机 `mvn` 默认可能挑到 JDK 25，Lombok 1.18.30 在其上不做注解处理，会报满屏"找不到符号 getXxx()"。
> 构建前显式指定 JDK 17，例如 Homebrew 安装的路径：
> `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`

CI（`.github/workflows/ci.yml`）会额外断言编译产物是 Java 17 字节码（class file major version = 61），
防止 `pom.xml` 漏写 `java.version` 后静默退回 Java 8。

## 已知边界

- **所有验证都停在单元级**：123 条用例全是 Mockito，没有起过 MySQL / Redis / RabbitMQ / Kafka，
  V1~V5 迁移与新增索引**未在真实库上执行过**；滑动窗口 Lua、`sync=true` 的互斥、死信补做、
  429 端到端、导出文件能否被 Excel 打开，都属于"代码与单测自洽"，不是实测结论。
- 微信支付走真实商户接口，本仓库未在沙箱完成端到端联调；回调验签、金额校验、退款幂等与补偿的逻辑有单测覆盖。
- WebSocket 握手改成必须带管理端 JWT，**管理端前端还没改**：前端未把登录令牌拼到 `ws://...?token=` 上时，
  来单/催单推送连不上。
- 限流维度只取 `remoteAddr`：挂到 Nginx/SLB 之后所有请求来源都是代理 IP，需要先在代理层配可信跳数再改。
- `order.created.DLT` 只声明、只有投递方，**没有消费者或重放工具**，是要接告警的队列。
- `media_object` 只登记本批之后的新上传：存量对象不会自动回收；`claimDeletion` 先置状态再调 OSS，
  OSS 删除失败会留下"标记已删、实际还在 bucket"的孤儿行（换取多实例不重复删）。
- TTL 抖动只覆盖 Spring Cache 这条路径，直接用 `StringRedisTemplate` 写的 key（营业状态、令牌黑名单、限流桶）没有抖动。
- 没有库存扣减：只有菜品起停售，高并发超卖场景需要另加库存表与乐观锁。
- 营业额统计结果仍是 `Double`，未打通全链路 `BigDecimal`。
- `WeChatPayUtil` / `HttpClientUtil` 每次请求新建 HTTP client，没有连接池与熔断。
- 接口文档（Swagger/knife4j）只在非 docker profile 下启用。

## 相关文档

- `项目升级完善清单.md`：分级待办与每一批改造的落地方式、行为变更清单。
- `Java全栈面试准备.md`：按"面试官会怎么追问"组织的项目实现细节与问答库。
