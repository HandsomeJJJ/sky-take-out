# 苍穹外卖 · Java 全栈面试准备

> 用法：每条控制在 3~6 句，直接照着念即可。**黑体是必背关键词**，面试官问到时必须吐出来。

---

## 0. 30 秒项目介绍（开场必背）

「项目是**苍穹外卖**，一套**外卖点餐系统**，分**管理端（B端 Web）**和**用户端（微信小程序）**两端。

后端 **SpringBoot 2.7 + MyBatis + MySQL + Redis**，用 **JWT** 做登录认证，**AOP** 做公共字段自动填充，**阿里云 OSS** 存图片，**微信支付 V3** 做支付，**WebSocket** 做来单提醒和催单，**RabbitMQ** 做订单超时取消，**Kafka** 做订单事件异步统计，**Apache POI** 导出运营报表。

我对原版做的主要改造是：**把订单超时取消从 Spring Task 定时扫表，改成 RabbitMQ 延迟消息**，并把订单创建事件通过 **Kafka** 解耦出去做异步统计，两个中间件都做了**开关降级**和**消费幂等**。」

---

## 1. 技术栈速查

| 层 | 技术 | 说明 |
|---|---|---|
| 框架 | SpringBoot 2.7.3 | 父工程统一管理依赖版本 |
| ORM | MyBatis + PageHelper | XML 动态 SQL + 注解混用 |
| DB | MySQL 8 + Druid 连接池 | |
| 缓存 | Redis（StringRedisTemplate / RedisTemplate） | 营业状态、分布式锁、消费去重 |
| 认证 | JWT（jjwt 0.9.1）+ HandlerInterceptor | 无状态登录 |
| 消息 | RabbitMQ（AMQP）+ Kafka（spring-kafka） | 延迟取消 / 事件流 |
| 实时 | WebSocket（@ServerEndpoint） | 来单提醒、催单 |
| 存储 | 阿里云 OSS | 图片上传 |
| 支付 | 微信支付 V3（wechatpay-apache-httpclient） | JSAPI 下单 + 退款 |
| 文档 | Knife4j（Swagger2） | 分组：管理端 / 用户端 |
| 报表 | Apache POI | 基于模板导出 Excel |
| 部署 | Dockerfile + docker-compose | MySQL/Redis/Kafka/RabbitMQ 一键起 |

工程结构：**sky-common（工具/常量/异常/属性）→ sky-pojo（entity/dto/vo）→ sky-server（controller/service/mapper/config/task/messaging）**。

---

## 2. 功能模块清单（被问"有哪些功能"时按这个背）

**管理端**：员工登录与 CRUD、分类管理、菜品管理（含口味）、套餐管理、订单管理（搜索/接单/拒单/取消/派送/完成）、工作台数据概览、营业额/用户/订单/Top10 统计、营业状态开关、报表导出。

**用户端**：微信登录、按分类浏览菜品套餐、购物车（加/减/清空）、下单支付、订单查询/取消/再来一单/催单、地址簿管理。

**订单状态机（背熟）**：`1 待付款 → 2 待接单 → 3 已接单 → 4 派送中 → 5 已完成 / 6 已取消`。

---

## 3. 核心实现细节（逐条背）

### 3.1 登录认证 JWT + 拦截器 + ThreadLocal

- 登录成功用 `JwtUtil.createJWT` 签发 token，**claims 里只放 userId/empId**，密钥和过期时间写在 `sky.jwt` 配置里。
- `WebMvcConfiguration` 注册两个拦截器：管理端拦 `/admin/**` 放行 `/admin/employee/login`；用户端拦 `/user/**` 放行登录和营业状态查询。
- 拦截器里 `JwtUtil.parseJWT` 解析失败 → 直接 `response.setStatus(401)`；成功则把 id 放进 **`BaseContext`（ThreadLocal\<Long\>）**。
- Service 层需要当前用户 id 时直接 `BaseContext.getCurrentId()`，**不用层层传参**，也是自动填充切面拿操作人的来源。

> 追问：为什么用 ThreadLocal？——**一次请求一个线程**，把登录态绑定到线程上，方法签名不用污染；用完必须 remove，否则**线程池复用会串号 + 内存泄漏**。

### 3.2 微信登录（小程序）

- 前端传 `code` → 后端调 `sns/jscode2session`（appid + secret + code）换 **openid**。
- 拿 openid 查 user 表，**查不到就自动注册**插入一条新用户，实现"无感注册"。
- 然后同样签发 JWT 返回 `UserLoginVO（id/openid/token）`。

### 3.3 公共字段自动填充（AOP）

- 自定义注解 `@AutoFill(OperationType.INSERT/UPDATE)` 打在 Mapper 方法上。
- 切面 `AutoFillAspect` 切入点：`execution(* com.sky.mapper.*.*(..)) && @annotation(...)`，**前置通知**。
- 通过 `MethodSignature` 拿到注解的操作类型，**反射调用 setCreateTime/setCreateUser/...** 赋值，操作人从 `BaseContext` 取。
- 好处：几十张表的 create_time/update_user 不用手写一遍。

### 3.4 全局异常处理

- `@RestControllerAdvice` + `@ExceptionHandler`。
- 业务异常统一继承 `BaseException`，Controller 里只管 `throw new XxxException(MessageConstant.XXX)`。
- 额外捕获 `SQLIntegrityConstraintViolationException`，从 `Duplicate entry 'zhangsan'` 里截出用户名，返回"xxx已存在"。

### 3.5 文件上传

- `CommonController.upload` → `MultipartFile` 取原始文件名截后缀，**UUID 重命名防覆盖**，调 `AliOssUtil` 上传，返回可访问 URL。

### 3.6 营业状态（Redis）

- 管理端 `PUT /admin/shop/{status}` 写 `SHOP_STATUS`；用户端 `GET /user/shop/status` 读，**不落库**，高频读直接走 Redis。

### 3.6.1 菜品/套餐缓存（Spring Cache）

- 三个缓存：`dishCache`（按分类）、`setmealCache`（按分类）、`setmealDishCache`（套餐内菜品）。
- 读：`@Cacheable(cacheNames="dishCache", key="#categoryId")`，**空列表也缓存**，防穿透。
- 写：**先更新 DB 再删缓存**（Cache Aside），起停售菜品时**连带清套餐缓存**（停售菜品会级联停售套餐）。
- `CacheManager` 用 **`TransactionAwareCacheManagerProxy`** 包装：删缓存延迟到**事务提交后**，避免"事务未提交先删缓存 → 并发读回填旧值"。
- 序列化：**JSON + 手动 default typing**（不开启的话反序列化回来是 `LinkedHashMap`），并用 `BasicPolymorphicTypeValidator` 限定反序列化包，防 RCE。
- 统一 **TTL 60 分钟**；value 不用 JDK 序列化（有反序列化漏洞风险）。

### 3.7 菜品 / 套餐 / 分类

- 新增菜品：菜品表 1 条 + 口味表 n 条，**`@Transactional`** 保证原子；主键靠 `useGeneratedKeys` 回显。
- 删除菜品前校验：**起售中不能删**、**被套餐关联不能删**。
- 菜品停售 → **级联停售包含它的套餐**；套餐起售 → 校验套餐内不能有停售菜品。
- 删除分类前校验是否关联菜品/套餐。

### 3.8 购物车

- 加购先查是否存在：**存在则 number+1（update），不存在则 insert**（区分 dishId / setmealId 补全名称图片价格）。
- 减购：number=1 就删记录，否则 -1。

### 3.9 下单（最容易深挖）

1. **Redisson 分布式锁** `order:submit:lock:{userId}`，防重复提交（watchdog 自动续期 + 可重入）。
2. 校验地址簿、购物车非空。
3. 插订单表 1 条（状态 1 待付款 / 支付状态未支付）→ 插订单明细 n 条 → **清空购物车** → **写本地消息表 `mq_message`（2 条：超时取消 + 统计）**，整体 `@Transactional`。
4. 发布 **`OrderCreatedEvent`**，`@TransactionalEventListener(AFTER_COMMIT)` 在**事务提交后**触发立即发送。
5. 发送失败由 **`MqMessageCompensator` 定时补偿**重发，超过 5 次进死亡态 —— 保证"业务成功则消息必达"。
6. 锁的释放注册在 `TransactionSynchronization.afterCompletion`，**事务提交或回滚后统一解锁**。

### 3.10 订单超时取消（重点中的重点）

**方案：RabbitMQ TTL + 死信队列**（不依赖延迟插件）。

```
下单提交 → 本地消息表 → order.delay.exchange → order.delay.queue（TTL 15min，无消费者）
                              ↓ 消息过期变死信
                          order.cancel.exchange → order.cancel.queue → 消费者
```

- 消费者执行 **`update orders set status=6 ... where id=? and status=1`**，**带状态的条件更新天然幂等**，重复投递也只会改一次。
- **可靠性靠本地消息表**：消息先落库（和订单同事务），再异步投递，投递失败由补偿任务重发，MQ 宕机也不会丢。
- 开关：`sky.messaging.rabbitmq.enabled`，`@ConditionalOnProperty` 控制装配，**本地默认关闭**。
- 兜底：`OrderTimeoutFallbackTask`，只在 MQ 关闭时启用（每分钟扫表），**主链路 MQ，降级定时任务**。

### 3.11 Kafka 订单事件与统计

- 下单提交后，本地消息表按 `bizType=ORDER_STATS` 投递 `order.created` 主题，**key = orderId**（同订单进同一分区，保证局部有序）。
- 消费者组 `sky-order-statistics`：先 `SETNX order:statistics:processed:{orderId}`（8 天过期）**去重**，再 `INCR order:statistics:created:{日期}` 累加当日下单量。
- 目的：**把统计类非核心逻辑从下单主链路摘出去**，下单接口只做写库，响应更快。

### 3.12 微信支付

- 下单：`WeChatPayUtil.pay()` 调 V3 JSAPI 下单拿 `prepay_id`，再做**二次签名**（appid/timeStamp/nonceStr/package 拼串，`SHA256withRSA`），返回给小程序调起支付。
- 回调 `/notify/paySuccess`：读 body + 请求头（`Wechatpay-Serial/Signature/Timestamp/Nonce`）→ **`AutoUpdateCertificatesVerifier` 验签 + 解密一体化**（`NotificationHandler.parse`，验签失败直接抛异常，防伪造回调）→ 取 `out_trade_no` → 改订单状态（幂等）→ **WebSocket 广播来单提醒** → 返回 `{code:"SUCCESS"}` 给微信。
- 退款：取消/拒单时若已支付，按订单**实际金额**退款，退款单号复用商户订单号。

### 3.13 WebSocket 实时推送（Redis Pub/Sub 广播）

- `@ServerEndpoint("/ws/{sid}")`，session 存**本节点内存**，`@OnOpen/@OnClose/@OnMessage` 维护。
- **跨节点推送靠 Redis Pub/Sub**：业务侧 `publish` 到频道 → 各节点 `MessageListener` 收到后推给**本节点持有的 session**（session 不可序列化，只能本地推）。
- 两种消息：`type=1 来单提醒`，`type=2 客户催单`，管理端页面收到后弹窗 + 语音。

### 3.14 定时任务

- `OrderTask`：每天凌晨 1 点，把**派送中且超过 60 分钟**的订单置为已完成；加 **Redis 分布式锁**，多实例只执行一次。
- 超时取消已交给 MQ（见 3.10）。

### 3.15 数据统计 + 报表导出

- 营业额 = `sum(amount) where status=5`；有效订单 = `count where status=5`；完成率 = 有效/总；客单价 = 营业额/有效订单。
- Top10：`order_detail join orders` group by 菜品名，`sum(number)` 倒序 limit 10。
- 导出：**读取 classpath 里的 xlsx 模板**，POI 填单元格，30 天明细逐行写入，最后通过 `response.getOutputStream()` 下载。

### 3.16 杂项

- 分页：`PageHelper.startPage(page, pageSize)`，**只对紧随其后的第一条 SQL 生效**，靠 ThreadLocal 传分页参数。
- 接口文档：Knife4j 分两组 Docket，扫 admin / user 两个包。
- 部署：`docker-compose` 起 MySQL、Redis、Kafka（KRaft 模式）、RabbitMQ、应用；应用走 `docker` profile，**依赖服务 healthcheck 通过才启动**。

---

## 4. 高频追问 · 标准回答

### Q1：订单超时取消为什么不用 Spring Task？

「Spring Task 是**单机内存调度**，四个硬伤：
1. **集群重复执行**——多实例部署时每个节点都会扫一遍表，要靠分布式锁抢，本身就变复杂；
2. **扫表压力大**——每分钟 `where status=1 and order_time < now-15min` 全表扫，数据量上来就扛不住，而且**精度只有分钟级**，误差最大一分钟；
3. **不可靠**——服务重启期间的任务**直接丢失**，没有补偿；
4. **耦合**——业务代码和调度绑死，改超时时间要改代码重新发布。

MQ 方案是**订单维度**的，下单就投递一条延迟消息，天然**精确到秒**、**不扫表**、**消息持久化不丢**。」

### Q2：为什么用 RabbitMQ 而不是 Kafka / RocketMQ 做延迟？

「Kafka **没有原生延迟消息能力**（要做只能自己搞时间轮 + 中间 topic，很别扭），它强在**高吞吐日志流**；RabbitMQ 原生有 TTL + 死信，做延迟最省事。RocketMQ 有**原生延迟等级**，如果是从零选型，RocketMQ 在延迟消息这块其实更合适——但项目里已经要 Kafka 做事件流了，延迟这块用 RabbitMQ 补位，属于**按场景选型**。」

### Q3：延迟消息还有哪些实现？你怎么选？

| 方案 | 说明 | 评价 |
|---|---|---|
| RabbitMQ TTL + 死信 | 队列设 TTL，过期转投死信交换机 | **项目用的**，无需插件，缺点是 TTL 只能队列级别统一（除非用插件的 per-message TTL） |
| rabbitmq_delayed_message_exchange 插件 | 交换机层面延迟，支持每条消息不同延迟 | 更灵活，但要装插件，镜像需运维配合 |
| RocketMQ 延迟等级 | 18 个固定等级 | 开箱即用，等级固定不够灵活 |
| Redis ZSet | score 存到期时间戳，定时轮询 | 轻量，但要自己做可靠性，容易丢 |
| 时间轮（Netty HashedWheelTimer） | 内存级，O(1) | 快但**不持久化**，宕机就丢 |

### Q4：消息会不会丢？怎么保证？

「我项目里**第一道防线是本地消息表**：消息和业务数据写进**同一个本地事务**，事务提交后再投递 MQ，投递失败由补偿任务重发，超过上限进死亡态告警——这就从根上解决了"业务成功了但消息没发出去"。

再往后是 MQ 本身的三层：
- **生产者**：RabbitMQ 开 `publisher-confirm` + `publisher-return`；Kafka 设 `acks=all` + `retries`。
- **Broker**：交换机、队列、消息都 **durable 持久化**；Kafka 靠多副本 ISR。
- **消费者**：RabbitMQ **手动 ACK**；Kafka **处理完再提交 offset**。」

### Q5：重复消费怎么办？

「**幂等**是必答题。我项目里用了两种：
1. **数据库条件更新**：`update ... where id=? and status=1`，状态已经变了就更新 0 行，天然幂等（RabbitMQ 取消订单用的这个）；
2. **Redis SETNX 去重表**：消费前先 `setIfAbsent(业务唯一键)`，成功才处理，TTL 给足业务周期（Kafka 统计用的这个，8 天）。

本地消息表的 `markSent` 也用 `where status=0` 条件更新，保证并发发送下只标记一次。更进一步可以建**消息去重表**，用 `msgId` 建唯一索引。」

### Q6：消息堆积了怎么排查？

「先看是**生产快还是消费慢**。消费慢就：加消费者实例（**注意不能超过分区数/队列数，否则空转**）、批量消费、把耗时逻辑（发短信、写统计）再拆出去。生产突增就限流削峰。紧急情况下可以**临时扩容消费者 + 降级非核心消费逻辑**，或者把堆积消息转存后慢慢回放。」

### Q7：你下单用的 Redis 分布式锁，细节讲讲？

「现在直接用 **Redisson 的 `RLock`**，但原理我清楚：
- 自研的话是 `SET key token NX EX 30`，**一条命令保证原子性**（不用 setnx + expire 两步，那会死锁）；解锁用 **Lua 脚本**先比对 token 再 del，**防误删别人的锁**。
- Redisson 在此基础上做了三件事：**watchdog 看门狗**（`leaseTime=-1` 时默认 30s，业务执行期间每 10s 自动续期，业务跑多久锁就持有多久）、**可重入**、**公平锁/联锁等更多原语**。
- 锁在**事务完成后**才释放（注册在 `afterCompletion`），防止前一个请求还没提交，下一个重复请求就进来；进程崩溃后 watchdog 停止，30s 后锁自动释放，不会死锁。」

### Q8：ThreadLocal 为什么内存泄漏？

「ThreadLocalMap 的 key 是**弱引用**，value 是强引用。线程来自**线程池长期不销毁**，key 被 GC 后 value 还在，占着内存。所以**用完必须 remove()**。我们项目在拦截器和异步场景都要注意，尤其异步线程里拿不到父线程的 ThreadLocal，得用 `TransmittableThreadLocal` 或手动传参。」

### Q9：JWT 讲一下 / 怎么让它失效？

- 三段：**Header（算法）· Payload（claims）· Signature（签名）**，用 `.` 拼接后 Base64URL。
- 服务端**不存会话**，靠密钥验签，**天然支持分布式**。
- 缺点：**一旦签发，过期前无法主动失效**。要做登出/踢人就得加一层 **Redis 黑名单**（或维护 token 版本号），校验时先查黑名单。
- 对比 Session：Session 存服务端、占内存、集群要共享；JWT 无状态但**没法即时作废**，且**别往 payload 里放敏感信息**（只是 Base64 不是加密）。

### Q10：@Transactional 什么时候会失效？

「五个经典：
1. 方法**不是 public**；
2. **同类内部方法调用**（没走代理，`this.xxx()`）；
3. 异常被 **try-catch 吞掉**没抛出去；
4. 抛的是**受检异常**且没配 `rollbackFor`；
5. **数据库引擎不是 InnoDB** 或方法被 `final` 修饰导致 CGLIB 无法代理。
我们下单方法是 public、走接口代理、异常直接抛，所以生效。」

### Q11：MyBatis 相关

- `#{}` 是**预编译占位符**，防 SQL 注入；`${}` 是**字符串拼接**，有注入风险，只用来动态表名/排序字段。
- **一级缓存**：SqlSession 级别，默认开；**二级缓存**：namespace 级别，跨 SqlSession，需要序列化、且**任何增删改都会清空**，实战中一般**关掉**，用 Redis 代替。
- 主键回显：`useGeneratedKeys="true" keyProperty="id"`。
- 动态 SQL：`<where>` `<set>` `<if>` `<foreach>`，本项目订单更新用的 `<set>` 实现"只更新非空字段"。

### Q12：PageHelper 原理？

「基于 **MyBatis 拦截器（Interceptor）**，拦截 `Executor.query`，从 `ThreadLocal` 里取出分页参数，**改写 SQL 拼上 limit**，另外再发一条 `count(*)` 查总数。所以 `startPage` 后面必须**紧跟**要分页的查询，中间不能插别的查询，否则会污染。」

### Q13：订单号怎么生成？会不会重复？

「我用**雪花算法**（`SnowflakeIdGenerator`）：1 位符号位 + 41 位时间戳 + 5 位机器 ID + 12 位序列号，`synchronized` 保证同毫秒内序列号递增。比 `System.currentTimeMillis()` 强在**毫秒级并发不撞号**、趋势递增利于索引。集群下不同实例配不同 workerId；时钟回拨会主动拒绝生成。也可以选数据库号段（Leaf）或 Redis INCR，但雪花最简单且不依赖外部组件。」

### Q14：支付回调怎么保证安全和幂等？

- **验签**：用官方 `AutoUpdateCertificatesVerifier` + `NotificationHandler.parse()`，把 `Wechatpay-Serial/Signature/Timestamp/Nonce` 头一起丢进去做**验签 + 解密一体化**，验签不过直接抛异常（`ValidationException`），从根上杜绝伪造"支付成功"回调。验签器还会定时更新平台证书，防证书轮换后失效。
- **幂等**：微信会**重复推送**直到收到 SUCCESS，所以改状态前**先判断订单当前状态**，已支付就直接返回成功，别重复加钱/重复发货。
- **响应**：必须返回 200 + `{code:"SUCCESS"}`，否则微信会持续重发。
- **回调地址必须是公网 HTTPS**，本地开发用内网穿透（项目配的是 cpolar 域名）。

### Q15：WebSocket 集群下怎么推送？

「session 存**单机内存**，多实例时管理端连 A 节点、支付回调打在 B 节点就推不到。我改成 **Redis Pub/Sub 广播**：业务侧 `publish` 到频道，每个节点通过 `MessageListener` 订阅，收到后推给**自己本地持有的 session**。因为 **session 不可序列化、不能存 Redis**，所以正确做法不是共享 session，而是**广播消息 + 各节点本地转发**。生产规模更大时可以上 Spring WebSocket + STOMP + RabbitMQ 做 broker。」

### Q16：订单列表有没有 N+1 问题？

「有。`pageQuery4User` 是先分页查订单，再**循环里逐条查订单明细**。订单数固定（比如 10 条/页）时可控，但更好的做法是**一次 `where order_id in (...)` 批量查出来再内存分组**，或者直接在 SQL 里 join 后用 `resultMap` 的 `collection` 映射。」

### Q17：缓存穿透 / 击穿 / 雪崩？

- **穿透**：查不存在的 key → 布隆过滤器，或缓存空值 + 短 TTL。
- **击穿**：热点 key 过期瞬间大量请求打到 DB → **互斥锁（Redis SETNX）重建**，或逻辑过期。
- **雪崩**：大批 key 同时过期 → **TTL 加随机值**打散。
- 本项目 Redis 用途：营业状态、分布式锁、消费去重、**菜品/套餐缓存**。缓存这块用的是 **Cache Aside + 事务提交后删缓存**（`TransactionAwareCacheManagerProxy`），写操作一律**删缓存而不是更新缓存**。后续可补：**TTL 随机值**打散、**延迟双删**、热点 key 用**逻辑过期**避免击穿。

### Q18：接口幂等怎么设计？

「三种常用：
1. **Token 机制**：进入页面先拿一个 token，提交时带上，服务端 `del token` 成功才处理（防重复提交）；
2. **唯一索引**：业务唯一键建 unique，靠 DB 兜底；
3. **状态机幂等**：`update ... where status=前置状态`，本项目取消订单、接单、派送、完成全是这个套路，改 0 行就说明状态不对，直接抛异常。」

### Q19：AOP 底层？

「**动态代理**。目标类**实现接口**用 **JDK Proxy**；**没实现接口**用 **CGLIB**（生成子类，所以不能代理 final 类和 final 方法）。SpringBoot 2.x 默认 `proxyTargetClass=true`，**统一走 CGLIB**。我们公共字段填充是 `@Before` 前置通知，作用在 Mapper 上，由 Spring AOP 生成代理对象织入。」

### Q20：过滤器、拦截器、切面区别？

「**Filter**（Servlet 规范，能拿到原始 request/response，最外层，乱码、跨域）→ **Interceptor**（Spring MVC，能拿到 Handler 方法，做登录校验、权限）→ **AOP**（方法级，粒度最细，能对任意 Bean 的方法织入，做日志、事务、自动填充）。」

### Q21：项目里 `allow-circular-references: true` 是干嘛的？

「SpringBoot 2.6 起**默认禁止循环依赖**，这个配置是把它放开。出现循环依赖说明**代码分层有问题**，正确做法是**重构**（抽取第三方类、或用 `@Lazy` 延迟注入、或 setter 注入），别靠配置兜着。顺带可以讲三级缓存：`singletonObjects` / `earlySingletonObjects` / `singletonFactories`，三级缓存解决的是**setter 注入的循环依赖**，构造器注入的循环依赖解决不了。」

### Q22：SpringBoot 自动配置原理？

「`@SpringBootApplication` 里的 `@EnableAutoConfiguration` → 加载 `META-INF/spring.factories`（2.7 也开始支持 `AutoConfiguration.imports`）里的配置类 → 靠 `@ConditionalOnXxx`（`@ConditionalOnClass`、`@ConditionalOnMissingBean`、`@ConditionalOnProperty`）**按条件决定是否装配** → 用户自己定义的 Bean 优先（`@ConditionalOnMissingBean`）。我项目里 MQ 的开关 `@ConditionalOnProperty` 就是这个机制的实际应用。」

---

## 5. MQ 改造 · 我做的亮点（主动抛出来加分）

**改造前**：`OrderTask` 每分钟扫表取消超时订单。

**改造后**：

1. **RabbitMQ TTL + 死信队列**做订单维度延迟取消，**精确到秒、不扫表、消息持久化**；消费端**条件更新幂等**。
2. **Spring 事件解耦**：下单只 `publishEvent`，由 `@TransactionalEventListener` 在**事务提交后**再投递 MQ —— **彻底解决"事务回滚但消息已发出"**的脏消息问题。
3. **Kafka** 承接订单创建事件做异步统计，**主链路瘦身**，下单响应更快。
4. **双开关降级**（`sky.messaging.kafka.enabled` / `sky.messaging.rabbitmq.enabled`），本地开发不装中间件也能跑；MQ 关闭时**自动回落定时任务兜底**。
5. **Kafka 侧 Redis SETNX 去重**，保证重复消费不影响统计结果。

讲完补一句：「如果只用一种中间件，我会选 **RocketMQ**，延迟消息 + 事务消息 + 高可靠一套搞定；现在双 MQ 是按场景分工——**RabbitMQ 管延迟，Kafka 管事件流**。」

---

## 6. 诚实清单：已知不足（被问"有什么可以改进"时主动说）

| 问题 | 现状 | 改进方向 |
|---|---|---|
| ~~订单号重复~~ | 已修复 | ✅ 雪花算法 `SnowflakeIdGenerator` |
| ~~WebSocket 单机~~ | 已修复 | ✅ Redis Pub/Sub 广播 |
| ~~支付回调用户身份~~ | 已修复 | ✅ 按 `orderNumber` 查询，不依赖登录态 |
| ~~菜品查询未缓存~~ | 已修复 | ✅ Spring Cache + Cache Aside + 事务提交后删缓存 |
| N+1 查询 | 订单明细循环单查 | `where order_id in (...)` 批量查 |
| 密码 MD5 | 无盐 | BCrypt / MD5 + 随机盐 |
| 循环依赖 | 配置层面放开 | 重构分层 |
| 无库存扣减 | 只有菜品起停售 | 高并发场景加库存 + 乐观锁 |

**主动说比被问出来强十倍**——说出改进方案，说明你清楚问题在哪。

---

## 7. 踩过的坑（真实，可聊）

1. **Lombok 1.18.20 在 JDK 17/25 下注解处理器失效**，编译报"找不到符号 getXxx()"（其实 `@Data` 根本没生成 getter）。解决：**升到 1.18.30**，并确保 Maven 跑在 **JDK 17** 上（本机默认 JDK 25 也会挂）。
2. **Spring 事件默认是同步的**，在事务内直接 publish 会导致"**事务还没提交，消费者已经查不到订单**"。解决：用 `@TransactionalEventListener`（默认 `AFTER_COMMIT`）。
3. **重复下单**：只靠前端防抖没用，后端必须加**分布式锁 + 状态机校验**。

---

## 8. 关键词速查（临考前扫一遍）

`JWT + 拦截器 + ThreadLocal` · `AOP 反射自动填充` · `RestControllerAdvice` · `PageHelper 拦截器改 SQL` · `MyBatis # 与 $` · `@Transactional 五失效` · `Redis SET NX EX + Lua 解锁` · `RabbitMQ TTL + 死信` · `TransactionalEventListener AFTER_COMMIT` · `Kafka key 分区有序 + SETNX 幂等` · `条件更新幂等 where status=?` · `WebSocket 单机 Map → Redis 广播` · `微信 V3 二次签名 + AES 解密` · `POI 模板导出` · `缓存三兄弟` · `循环依赖三级缓存` · `ConditionalOnProperty 条件装配`
