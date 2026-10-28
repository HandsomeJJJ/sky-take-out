# 苍穹外卖 · Java 全栈面试准备

> 用法：每条控制在 3~6 句，直接照着念即可。**黑体是必背关键词**，面试官问到时必须吐出来。

---

## 0. 30 秒项目介绍（开场必背）

「项目是**苍穹外卖**，一套**外卖点餐系统**，分**管理端（B端 Web）**和**用户端（微信小程序）**两端。

后端 **SpringBoot 2.7 + MyBatis + MySQL + Redis**，用 **JWT** 做登录认证，**AOP** 做公共字段自动填充，**阿里云 OSS** 存图片，**微信支付 V3** 做支付，**WebSocket** 做来单提醒和催单，**RabbitMQ** 做订单超时取消，**Kafka** 做订单事件异步统计，**Apache POI** 导出运营报表。

我对原版做的主要改造是：**把订单超时取消从 Spring Task 定时扫表，改成 RabbitMQ 延迟消息**，并把订单创建事件通过 **Kafka** 解耦出去做异步统计，两个中间件都做了**开关降级**和**消费幂等**。

在此之后我按"钱、权限、可靠性、口径"的顺序做了四轮收口：金额与打包费全部由服务端算、C 端读写都带归属校验；消息链路补了**本地消息表 + 送达确认 + 死信消费者 + 与 MQ 并存的兜底扫描**；缓存补了**互斥回源和 TTL 抖动**；限流改成**滑动窗口并返回 429 + `Retry-After`**；报表改成**半开区间 + 按天聚合**，销量排名按 SKU id 分组。目前是 **123 条单元用例**，CI 里断言产物必须是 Java 17 字节码。」

---

## 1. 技术栈速查

| 层 | 技术 | 说明 |
|---|---|---|
| 框架 | SpringBoot 2.7.3 + **Java 17** | 父工程统一管理依赖版本；pom 显式声明 `java.version=17`，产物字节码 major=61 |
| ORM | MyBatis + PageHelper | XML 动态 SQL + 注解混用 |
| DB | MySQL 8 + Druid 连接池 | |
| 缓存 | Redis（StringRedisTemplate / RedisTemplate / Spring Cache） | 营业状态、分布式锁、消费去重、**菜品套餐缓存（抖动 TTL + `sync=true` 互斥回源）** |
| 限流 | **AOP + Redis ZSET 滑动窗口（Lua）** | 超限返回 **HTTP 429 + `Retry-After`**；登录口另有账号维度失败锁定 |
| 认证 | JWT（jjwt **0.12.6**）+ HandlerInterceptor + **Redis 登出黑名单** | 无状态登录，但 logout 与被禁用都能真正让令牌失效 |
| 消息 | RabbitMQ（AMQP）+ Kafka（spring-kafka） | 延迟取消 / 事件流；两侧都有**死信队列**，Rabbit 侧死信已配消费者 |
| 实时 | WebSocket（@ServerEndpoint）+ Redis Pub/Sub | 来单提醒、催单；**握手需要管理端 JWT**（验签 + `empId` 声明） |
| 存储 | 阿里云 OSS + **`media_object` 对象台账** | 图片上传（大小/后缀/文件头三重校验）；**引用计数 + 保留期后才物理删除** |
| 支付 | 微信支付 V3（wechatpay-apache-httpclient） | JSAPI 下单 + 退款 |
| 文档 | Knife4j（Swagger2） | 分组：管理端 / 用户端 |
| 报表 | Apache POI **5.2.5** | 基于模板导出 Excel |
| 部署 | Dockerfile + docker-compose + **GitHub Actions** | 一键起栈；CI 断言产物必须是 major=61 |
| 数据库版本 | **Flyway** | schema 与索引随应用启动自动落地 |
| 安全 | **BCrypt**（spring-security-crypto） | 员工密码有盐慢哈希 |
| JSON | **Jackson**（统一 `JsonUtils`） | 已移除 fastjson |

工程结构：**sky-common（工具/常量/异常/属性）→ sky-pojo（entity/dto/vo）→ sky-server（controller/service/mapper/config/task/messaging）**。

---

## 2. 功能模块清单（被问"有哪些功能"时按这个背）

**管理端**：员工登录与 CRUD（禁用即时失效、不能自我禁用、列表不回传口令哈希与身份证）、分类管理、菜品管理（含口味）、套餐管理、订单管理（搜索/接单/拒单/取消/派送/完成）、工作台数据概览、营业额/用户/订单/Top10 统计、营业状态开关（落库并真实拦单）、退款记录、报表导出。

**用户端**：微信登录、按分类浏览菜品套餐、购物车（加/减/清空）、下单支付、订单查询/取消/再来一单/催单、地址簿管理、**站内订单通知（列表/未读数/标记已读）**。

**订单状态机（背熟）**：`1 待付款 → 2 待接单 → 3 已接单 → 4 派送中 → 5 已完成 / 6 已取消`。

---

## 3. 核心实现细节（逐条背）

### 3.1 登录认证 JWT + 拦截器 + ThreadLocal

- 登录成功用 `JwtUtil.createJWT` 签发 token，**claims 里只放 userId/empId**，密钥和过期时间写在 `sky.jwt` 配置里。
- `WebMvcConfiguration` 注册两个拦截器：管理端拦 `/admin/**` 放行 `/admin/employee/login`；用户端拦 `/user/**` 放行登录和营业状态查询。
- 拦截器里 `JwtUtil.parseJWT` 解析失败 → 直接 `response.setStatus(401)`；成功则把 id 放进 **`BaseContext`（ThreadLocal\<Long\>）**。
- Service 层需要当前用户 id 时直接 `BaseContext.getCurrentId()`，**不用层层传参**，也是自动填充切面拿操作人的来源。
- **令牌的两种失效路径**（JWT 无状态，服务端删不掉已签发的令牌，只能"拒绝接受"）：
  - `logout` 拿得到令牌原文 → 黑名单：key 存令牌的 **SHA-256**（不把仍然可用的凭证原样落到 Redis），TTL 取配置的存活时间。
  - **禁用账号**拿不到对方令牌原文 → 按员工记一个"作废时间点" `jwt:valid-after:{empId}`，拦截器解析出令牌的 `iat`，早于这个时间点签发就 401。否则管理员被禁用后，他手上的令牌还能用满 2 小时 TTL。
  - 两种校验都是 **fail-open**：Redis 抖动时放行并打 error —— 它们是登录态的增强校验，签名校验仍然执行；fail-closed 会让一次 Redis 抖动把全站变成 401。
- 员工侧还有两条护栏：**不能禁用当前登录的账号**（点完自己就出不去了，而且系统里可能没有第二个能启用它的账号）；`EmployeeDTO` 没有 `status` 字段，所以编辑接口改不动状态，禁用只能走 `/admin/employee/status/{status}` 这条有护栏的路径。

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
- 上传成功后**登记进 `media_object` 表**（对象名、URL、状态=在用）。理由：bucket 里的对象如果没有台账，就只有一个方向 —— 越传越多，永远没人知道哪些能删。
- 回收**不在删除商品时同步做**：菜品换图/删除只让对象进入"待回收"，且要先确认 `dish`、`setmeal`、`shopping_cart`、`order_detail` **四张表都不再引用**这个 URL；真的物理删除还要再等保留期（默认 180 天）。因为订单明细里存的就是同一个 URL，删早了用户回看自己的历史订单就是 404 图。
- 到期回收由 `MediaRetentionTask`（每天 03:30 + Redis 分布式锁）批量做：删除前**二次确认引用**（待回收期间可能有别的商品又填了同一个 URL），再用 `update ... set status=2 where id=? and status=1` **领取删除权**，多实例只有抢到的那一次会真的调 OSS。
- 引用计数用的是 `order_detail.image(64)` 前缀索引 —— 这张表行数最多，不建索引这条 count 就是全表扫。

### 3.6 营业状态（DB 为准 + Redis 缓存）

- 状态落在单行表 `shop_status`（`id=1`），管理端 `PUT /admin/shop/{status}` **先写库再刷缓存**；用户端 `GET /user/shop/status` 读 Redis（TTL 10 分钟），读不到就回源 DB，DB 也没有则初始化一行 —— **不再出现"缓存丢了就默认营业中"**。
- Redis 抖动时降级读库，不让下单链路因为一个状态查询整体失败。
- 这个开关**真的会拦业务**：打烊时加购与下单都抛业务异常"门店已打烊，暂不接单"，而不是只给前端一个展示字段。
- 缓存只写 Redis 的原始版本是有意为之的历史包袱：状态是"要不要收钱"的判据，必须能和订单一起被审计。

### 3.6.1 菜品/套餐缓存（Spring Cache）

- 三个缓存：`dishCache`（按分类）、`setmealCache`（按分类）、`setmealDishCache`（套餐内菜品）。
- 读：`@Cacheable(cacheNames="dishCache", key="#categoryId", sync=true)`，**空列表也缓存**，防穿透。
- **防击穿**：`sync=true` + 底层换成 `RedisCacheWriter.lockingRedisCacheWriter`。热点分类的 key 过期那一瞬间，只有抢到互斥锁的那一条线程回源，其余线程在原地等它的结果，而不是几十条一起涌向数据库。
- **防雪崩**：自定义 `JitteredTtlRedisCacheWriter` 包在写器外面，每个 key 的 TTL 在基准值 **±20% 随机散开**（60 分钟变成 48~72 分钟）。固定 TTL 的问题是自己造雪崩 —— 同一批 key 同一秒集体过期，过期后的第一波流量全是数据库穿透。
- 包装类只做两件事：**给 TTL 加抖动**，其余 `get/putIfAbsent/remove/clean/统计` 全部原样委托。`putIfAbsent` 的返回值必须透传，否则 `sync=true` 的互斥语义就废了；`withStatisticsCollector` 要委托到真写器，不然命中率统计恒为 0。
- 抖动只作用于 Spring Cache 这条路径；直接用 `StringRedisTemplate` 写的 key（营业状态、令牌黑名单、限流桶）没有抖动，也不该有 —— 那些是给业务读的，不是给缓存挡流量的。
- 写：**先更新 DB 再删缓存**（Cache Aside），起停售菜品时**连带清套餐缓存**（停售菜品会级联停售套餐）。
- `CacheManager` 用 **`TransactionAwareCacheManagerProxy`** 包装：删缓存延迟到**事务提交后**，避免"事务未提交先删缓存 → 并发读回填旧值"。
- 序列化：**JSON + 手动 default typing**（不开启的话反序列化回来是 `LinkedHashMap`），并用 `BasicPolymorphicTypeValidator` 限定反序列化包，防 RCE。
- 基准 **TTL 60 分钟**（再加抖动）；value 不用 JDK 序列化（有反序列化漏洞风险）。

### 3.7 菜品 / 套餐 / 分类

- 新增菜品：菜品表 1 条 + 口味表 n 条，**`@Transactional`** 保证原子；主键靠 `useGeneratedKeys` 回显。
- 删除菜品前校验：**起售中不能删**、**被套餐关联不能删**。
- **删除菜品/套餐时，同一个事务里把所有用户购物车中指向它的行一起清掉**：`dish` 行删了之后 `shopping_cart.dish_id` 就是悬空引用，下单复核会把整单拒掉，而用户在购物车里既看不到原因也删不掉，只能靠清购物车解决 —— 这不是体验问题，是数据一致性。
- 菜品停售 → **级联停售包含它的套餐**；套餐起售 → 校验套餐内不能有停售菜品。停售**不**清购物车：保留意向、由下单复核兜住，用户还能把别的换掉再下。
- 删除分类前校验是否关联菜品/套餐。

### 3.8 购物车

- **加购两道闸**：门店必须在营业（打烊时加进来的菜只能到下单才报错，用户会以为还能点）、商品必须在售（停售或查不到都拒绝，并且价格/名称/图片都取菜品表当前值，不接受客户端传的价格）。
- 加购先查是否存在：**存在则 `update shopping_cart set number = number + 1 where id=? and number < 99`（数据库内自增，避免"读出来 +1 再覆盖写回"在并发点击时丢失更新），不存在则 insert**。
- 减购：数据库内自减，减不动了（数量为 1 或已被别人减掉）就删行；删除必须带 `and user_id = 当前用户`。
- 唯一约束落在生成列上：`item_key = CONCAT(COALESCE(dish_id,0),'-',COALESCE(setmeal_id,0),'-',COALESCE(dish_flavor,''))` + `unique(user_id, item_key)`，因为 dish_id/setmeal_id 必有一列为 NULL，而 MySQL 唯一索引把 NULL 视为互不相等。

### 3.9 下单（最容易深挖）

1. **先判营业状态**：打烊直接拒绝，且这一步在加锁之前，不给无效请求占锁。
2. **Redisson 分布式锁** `order:submit:lock:{userId}`，防重复提交（watchdog 自动续期 + 可重入），锁释放注册在 `TransactionSynchronization.afterCompletion`，**事务提交或回滚后统一解锁**。
3. 校验地址簿（必须 `id + user_id` 都命中）、购物车非空。
4. **下单前复核**：把购物车里的 `dishId/setmealId` 一次 `in` 批量查回来，校验**当前仍在售**并以**当前价格**重算，任一行不可售就整单拒绝并点名商品名（不是静默少收，也不是 N+1）。
5. **金额与打包费都由服务端算**：总额 = Σ(现价 × 数量) + 打包费；打包费 = 配置单价 × 总份数并受上限封顶，DTO 里没有任何金额字段。
6. 插订单表 1 条（状态 1 待付款 / 未支付）→ 插订单明细 n 条（名称、图片、单价全部取复核后的当前值）→ **清空购物车** → **写本地消息表 `mq_message`（2 条：超时取消 + 统计）**，整体 `@Transactional`。
7. 发布 **`OrderCreatedEvent`**，`@TransactionalEventListener(AFTER_COMMIT)` 在事务提交后把发送任务**丢进独立线程池**（不在 HTTP 线程等 broker 确认）；发送失败由 **`MqMessageCompensator` 定时补偿**重发 —— 保证"业务成功则消息必达"。

> 追问："加购之后商家把菜下架了怎么办？"——**下单那一刻复核**，购物车行只是"用户想买什么"的意向，价格与在售状态一律以商品表当前值为准；已删除的 SKU 查不到，同样按下架处理。菜品/套餐被删除时会在同一个事务里清掉所有用户购物车里指向它的行，否则用户会面对一条"永远下不了又删不掉"的购物车记录。

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
- **死信队列有消费者**（`RabbitOrderTimeoutDeadLetterConsumer`）：主消费者 nack 且不 requeue 的坏消息落在 `order.cancel.dlq`，之前这里是黑洞 —— 订单会永久停在待支付，而**没有任何第二条路径会去看它**。现在能解析的用同一个条件更新补做取消并通知用户，解析不了的留 error 痕迹给告警。
- **死信消费者无论补做成败都 `basicAck`**：项目配的是 `acknowledge-mode: manual`，不 ack 的消息会在通道关闭时重新入队，死信消费者就会反复吞自己刚处理过的消息。死信是这条消息的最后一站，异常一律不外抛。
- 兜底：`OrderTimeoutFallbackTask` **不再和 MQ 互斥**（原来条件是 `rabbitmq.enabled=false` 才创建，等于假设"消息一定会被消费成功"）。MQ 正常时它只是每分钟多一次很便宜的索引扫描，MQ 异常时它是唯一出口；重复取消靠条件更新落到 0 行。
- 单笔失败不带走整批：循环里逐单 try/catch，整批失败计数打 error。这层很关键 —— `@Scheduled` 的异常默认被调度器吞掉，"任务跑过了"和"任务什么都没做"在日志上看起来一样。

### 3.11 Kafka 订单事件与统计

- 下单提交后，本地消息表按 `bizType=ORDER_STATS` 投递 `order.created` 主题，**key = orderId**（同订单进同一分区，保证局部有序）。
- 消费者组 `sky-order-statistics`：先 `SETNX order:statistics:processed:{orderId}`（8 天过期）**去重**，再 `INCR order:statistics:created:{日期}` 累加当日下单量（key 按天分片并挂 180 天 TTL，不挂就永久累积）。
- 目的：**把统计类非核心逻辑从下单主链路摘出去**，下单接口只做写库，响应更快。
- **消费失败不再吞消息**：原来 `catch (Exception)` 后只打日志，offset 照常提交 —— 这条统计就永久丢了，也没人能从日志里补回来。现在异常一律交回容器：
  - `DefaultErrorHandler` + `ExponentialBackOff`（1s 起、×2、单次上限 8s、总时长上限 15s）退避重试；
  - 重试用尽由 `DeadLetterPublishingRecoverer` 落到 **`order.created.DLT`**（同分区，topic 在启动时显式声明，不靠 broker 自动建）；
  - `SerializationException` / `IllegalArgumentException` 列进**不可重试清单**：报文结构坏了重试一万次也一样，直接进死信并留完整现场。
- **去重 key 的补偿细节**：`setIfAbsent` 已经占了位、但 `increment` 抛异常时，必须先把 `order:statistics:processed:{orderId}` 删掉再抛。不删的话，重投会被去重逻辑当成"已处理"直接跳过，这一单统计就永久少算一次 —— 这是那种只在故障场景才暴露的顺序依赖。
- 下一步：`order.created.DLT` 已经声明并接住重试耗尽的消息，接下来给它配一个重放/告警入口（和 Rabbit 那条死信队列已经有消费者这个状态对齐）。

### 3.12 微信支付与退款闭环

- 下单：`WeChatPayUtil.pay()` 调 V3 JSAPI 下单拿 `prepay_id`，再做**二次签名**（appid/timeStamp/nonceStr/package 拼串，`SHA256withRSA`），返回给小程序调起支付。**发起支付前必须校验订单仍是"待付款"**：超时取消的单 `pay_status` 还是 0，只判支付状态就等于允许给一张已取消的订单付钱。
- 回调 `/notify/paySuccess`：读 body + 请求头（`Wechatpay-Serial/Signature/Timestamp/Nonce`）→ **验签 + 解密一体化**（`NotificationHandler.parse`，验签失败直接抛异常，防伪造回调）→ 只接受 `trade_state=SUCCESS` → **比对回调 `amount.total` 与订单应付（同一套元转分口径）** → `where pay_status=0 and status=1` 条件更新入账 → WebSocket 广播来单提醒 + 给用户写一条支付通知 → 返回 `{code:"SUCCESS"}`；处理失败返回 **HTTP 500 + FAIL** 让微信重试。
- **迟到支付**：条件更新影响 0 行时不是一句 warn 就过 —— 重看订单，若"已取消且未入账"就把支付状态留痕（`pay_status 0→1`，订单状态仍是已取消），随后**自动发起全额退款**并通知用户。钱进来必须留下痕迹，既不承认也不退是资损方向。
- 退款：**`order_refund` 表 + 状态机**（0 待发起 / 1 微信已受理 / 2 成功 / 3 失败待重试 / 4 超限转人工）。
  - 退款单号 `RF + 订单号` 并建**唯一索引**，`insert ... on duplicate key update id=id` —— 重复点击、消息重投、并发补偿全部撞在这一个键上，所以"同一笔订单退两次"在数据库层就不成立，而不是靠调用前查一次（查完再写之间有竞态）。
  - **不加 `@Transactional`**：申请退款是一次外部 HTTP 调用，包进事务会把数据库连接按住到微信响应为止，微信慢的时候连接池会被拖空。记录先落库、调用结果再单独更新，任何一步崩溃都能被补偿任务捡起来。
  - `/notify/refundSuccess` 接收微信退款结果：验签解密后取 `out_refund_no`/`refund_status`/`amount.refund`，**回调金额与申请金额不一致就拒绝确认并回 FAIL**（宁可让微信重试，也不能先告诉用户"已到账"）；确认成功时靠 `where status not in (2,4)` 的条件更新保证重复回调不会二次推进、也不会再发一遍通知。
  - `RefundCompensator` 每分钟带分布式锁扫 `status in (0,3) and next_retry_time<=now` 重投，退避 1→120 分钟，8 次封顶后转人工并打 error。

### 3.13 订单通知与 WebSocket 推送

- **站内通知表 `order_notification`**：接单、拒单（含拒单原因）、派送、完成、用户取消、超时取消（MQ 消费者与兜底任务两条路径都写）、派送超时自动完成、退款到账 —— 每个状态变更都留一行，用户端 `/user/notification/list`、`/unread-count`、`/read` 三个接口拉取，`user_id` 只取登录态不接受入参。
- **为什么不用长连接定向推**：`@ServerEndpoint("/ws/{sid}")` 的 `sid` 是前端随机生成的，**没有和用户绑定**，做不了"只推给下单的那个人"；小程序侧 WebSocket 本身不稳定。所以定向触达走站内通知，实时提醒继续走广播。
- **握手现在要鉴权**：`/ws/{sid}` 是管理端的来单/催单广播通道，之前谁连上都能收到别人店铺的订单号（里面带金额和用户信息）。现在地址改成 `ws://host/ws/{sid}?token=<管理端 JWT>`，握手时校验：
  - 必须**用管理端密钥验签通过**，还要求载荷里有 `empId` 声明 —— 用户端令牌是另一套密钥，签不出这个组合，所以 C 端令牌打不进管理端通道；
  - 不通过就立刻 `session.close()`，并且**不登记进会话表**；
  - 鉴权器（Spring Bean）还没注册上时**一律拒绝**（fail-closed）：装配失败不能变成"裸奔"。
- 实现上的坑：`@ServerEndpoint` 的实例是**容器按连接 new 出来的，不是 Spring Bean**，`@Autowired` 注不进来。所以由 `WebSocketAuthenticator` 这个 `@Component` 在 `@PostConstruct` 里把自己塞到 `WebSocketServer` 的静态字段上，`@OnOpen` 再回调它。
- 顺手修的一个并发 bug：同一 `sid` 重连时，**旧**会话的 `@OnClose` 可能晚于**新**会话的 `@OnOpen` 到达，`remove(sid)` 会把刚建好的新会话摘掉，表现为"页面连着但收不到推送"。改成 `onClose(Session, sid)` + `SESSION_MAP.remove(sid, session)` **按值摘除**。
- WebSocket 跨节点：session 存**本节点内存**（不可序列化），业务侧 publish 到 Redis 频道，各节点 `MessageListener` 收到后推给本地 session；两种消息 `type=1 来单提醒`、`type=2 客户催单`，给管理端页面弹窗 + 语音。
- 通知写入是 **best-effort**：包 try/catch 打 error，绝不因为一条通知失败把已经完成的改状态/退款回滚或对外报错。

### 3.14 定时任务

- `OrderTask`：每天凌晨 1 点，把**派送超时的订单**置为已完成；加 **Redis 分布式锁**，多实例只执行一次。
- **判定基准是"开始派送时间"，不是下单时间**：商家 40 分钟后才派送，用下单时间算就只剩 20 分钟派送窗口，用户还没收到餐，系统已经把它置成"已完成"并计入营业额 —— 营业额口径因此虚高。做法是 `delivery` 动作走 `startDelivery`，同时写 `delivery_start_time`（V4 迁移加列 + `(status, delivery_start_time)` 联合索引），扫描条件用 `coalesce(delivery_start_time, order_time)`，历史单没有这一列也不会因为空值永远扫不到。
- 阈值**可配**：`sky.order.auto-complete-after-minutes`（默认 120 分钟），原来硬编码 60 分钟。改超时不该需要发版。
- 状态推进用 `where id=? and status=4` 的条件更新，不用"查出来改字段整行 update"：整行 update 会把用户在这期间改过的字段一起覆盖掉，和"用户点完成/商家派送"并发时还会互相盖状态。
- 超时取消已交给 MQ（见 3.10），`OrderTimeoutFallbackTask` 作为对账兜底**与 MQ 并存**。
- 调度池：`spring.task.scheduling.pool.size=4`。`@Scheduled` 默认**单线程**，一个任务卡住（慢 SQL、MQ 超时）会把其它定时任务全部拖死。

### 3.15 数据统计 + 报表导出

- 营业额 = `sum(amount) where status=5`；有效订单 = `count where status=5`；完成率 = 有效/总；客单价 = 营业额/有效订单。
- **时间区间一律半开 `[begin 00:00, end+1 00:00)`**：之前是 `order_time > #{begin}` / `< #{end}` 配 `LocalTime.MIN/MAX`，`00:00.000` 整点下单的行被两头排除，23:59:59.999999 之后到次日 00:00 之间落库的行不属于任何一天。所以这批改完之后报表数字会比以前**略高**，不是多算，是把漏的进行来了。
- **不再逐日循环查询**：一条 `group by date(order_time)` 的聚合（`dailyOrderStatistics`）就把区间内每天的营业额/总单/有效单取回来，内存里按日期 map、缺的日期补 0。导出 30 天原来是 120+ 次查询，现在是 2 次（订单 + 用户）。
- **总用户数**原来是"每天单独 count 一次全表"，改成 `countUntil(begin)` 取一次区间起点的存量，再逐日累加当日新增 —— 同一个口径，一条查询。
- 日期守卫：`begin/end` 为空、`begin > end`、跨度超过 366 天都在服务入口直接拒绝。这里原来是 `while(!begin.equals(end)) begin=begin.plusDays(1)`，**一个倒着填的日期参数就能把服务跑到内存耗尽**。
- Top10：**按 SKU 的 id 分组**（`coalesce(dish_id,0)` + `coalesce(setmeal_id,0)`）而不是按 `order_detail.name`。按名字分组有三个口径问题：菜品改名后历史销量断档、两个同名菜品被合并、套餐和单品同名也被合并。分组键换成 id 之后改名前后仍是同一个商品；展示名取窗口内出现过的名字之一，要精确到"当前名称"再关联商品表即可，它不影响销量数字本身。
- 导出：**读取 classpath 里的 xlsx 模板**，POI 填单元格，30 天明细逐行写入，最后通过 `response.getOutputStream()` 下载。
- **导出响应头**：`Content-Type` 指到 xlsx + `Content-Disposition: attachment; filename="..."; filename*=UTF-8''...`。不设置时浏览器会尝试原地渲染，文件名退化成 URL 最后一段，导出的文件没法归档。中文文件名走 RFC 5987：同时给 ASCII 回退名和 `filename*`，因为直接拼 `filename=中文名` 是不合法的，部分浏览器会截断。
- 关于"流式导出"：这里我**没有**换 SXSSF。导出行数固定 30 行，内存里 `XSSFWorkbook` 的成本可以忽略，真正的开销在原来每单元格一次查询上 —— 那个已经靠聚合查询消掉了。换成 SXSSF 只是把同一件事做复杂。数据量真上去（按门店/按 SKU 明细导出）才需要游标 + 分批写。

### 3.16 杂项

- 分页：`PageHelper.startPage(page, pageSize)`，**只对紧随其后的第一条 SQL 生效**，靠 ThreadLocal 传分页参数。
- 接口文档：Knife4j 分两组 Docket，扫 admin / user 两个包。
- **菜品列表的 N+1**：`listWithFlavor` 原来是「查 N 个菜品 → 循环里每个菜品单查一次口味」，一个分类 30 个菜就是 31 条 SQL，而这是 C 端浏览菜单的热路径。改成一次 `where dish_id in (...)` 批量查回来，`groupingBy(dishId)` 在内存里归位。有单测锁住"只查一次"（`verify(dishFlavorMapper, times(1)).getByDishIds(...)`、`never()).getByDishId(...)`）。订单列表那边的明细也是同一套做法（`getByOrderIds` 批量 + 分组），不靠缓存糊过去。
- **个人信息（PII）三条线**：
  - 响应体：员工列表 SQL 从 `select *` 改成显式列，**不再把 BCrypt 哈希和身份证号整页带回前端**。口令散列到了能翻员工列表的人手里，离线爆破只是时间问题。
  - 日志：`Employee` / `EmployeeDTO` 的 `password`、`phone`、`idNumber` 加 **`@ToString.Exclude`**，控制器日志只留 `id` + `username`。理由不是"这行日志现在不对"，而是**下一行 `log.info("{}", employee)` 也会漏** —— 在实体上堵住比在每一处调用点小心更可靠。
  - 环境：SQL 日志（`com.sky.mapper: debug`）只在 dev profile 开；生产开着会把手机号、收货地址、订单金额整条打进制品日志，而**日志的可见范围通常远大于数据库**。

### 3.17 Docker 部署（一条命令起全栈）

- **Dockerfile 多阶段构建**：
  - `builder` 阶段用 `maven:3.9.9-eclipse-temurin-17` 编译打包；
  - `runtime` 阶段只保留 `eclipse-temurin:17-jre` + Jar，**镜像体积从几百 MB 缩到 ~200MB**；
  - 先 `COPY pom.xml` 再 `dependency:go-offline`，**利用层缓存**——依赖没变就不重新下载。
- **为什么 JDK 17 而不是 8**：Lombok 1.18.30 的注解处理器在 JDK 8 下编译会失效（报"找不到符号 getXxx"），且 JDK 8 已停止免费维护。
- **健康检查**：引入 **Spring Boot Actuator**，暴露 `/actuator/health`；容器 `healthcheck` 用 `curl -sf` 打这个端点，`status=UP` 返回 200、`DOWN` 返回 503。
- **启动顺序**：`depends_on` + `condition: service_healthy`，**等 MySQL/Redis/Kafka/RabbitMQ 健康了应用才启动**（普通 `depends_on` 只等"容器起来"，不等"服务就绪"）。
- **数据卷**：`mysql-data`/`redis-data`/`kafka-data`/`rabbitmq-data` 用命名卷，**容器删了数据还在**；`mq_message.sql` 挂到 MySQL 的 `/docker-entrypoint-initdb.d` 首次初始化自动建表。
- **容器网络**：同一 compose 网络内**直接用服务名互访**（`mysql`、`redis`、`kafka`、`rabbitmq`），`application-docker.yml` 里就是写服务名而非 localhost。
- **非 root 运行 + 时区**：镜像内建 `appuser` 运行，`TZ=Asia/Shanghai` 保证 `LocalDateTime.now()` 是北京时间。

### 3.18 接口限流（AOP + Redis）与登录防爆破

- 自定义 `@RateLimit(limit, window, key)` 注解 + `RateLimitAspect` 切面。
- **算法是滑动窗口，不是固定窗口**：Redis 一个 ZSET，member=请求唯一标识、score=到达毫秒。一段 Lua 里顺序做完「`ZREMRANGEBYSCORE` 清掉窗口外的 → `ZCARD` 计数 → 未满就 `ZADD` + `PEXPIRE` → 已满就返回最早那条记录滑出窗口还需要等待的毫秒数」。
- 为什么不用固定计数器（`INCR` + `EXPIRE`）：**窗口边界能放过 2 倍流量** —— 前一窗口末尾 5 条 + 后一窗口开头 5 条，两个窗口各自都没超限，但任意 10 秒里其实过了 10 条。对下单这种接口，这就是"限流形同虚设的一瞬间"。
- 为什么必须在一段 Lua 里：清理、读计数、写入、续期拆开就有竞态 —— 两条线程各自读到"还差一个"，就都放行。Redis 单线程执行脚本，天然原子。
- **超限返回 HTTP 429 + `Retry-After`**：脚本把"该等多久"算出来，异常带着它走，全局异常处理器写响应头。之前它是 `BaseException` 的一种，返回 `200 + code=0`，按 HTTP 状态分支的客户端拦截器会把被限流当成成功。
- **分桶维度**：已登录按用户 id（`uid:`），**未登录按客户端 IP**（`ip:`，只取 `getRemoteAddr()`，不信 `X-Forwarded-For` —— 那是客户端可自填的头，拿它当限流维度等于把限流开关交给攻击者）。原来未登录全部归进一个 `anon` 桶，一个 NAT 出口就能让所有真实用户一起被限。
- 应用点：下单（10s/5 次）、催单（10s/3 次）、**管理端登录（60s/10 次）**、**小程序登录（60s/30 次，这个接口每次都要外呼微信）**。
- **账号维度的失败锁定**（`LoginAttemptGuard`）：密码错误计数，15 分钟内 5 次就把该账号临时锁定，登录成功清零；计数与锁定键的写入在同一段 Lua 里原子完成。
- 一个反直觉的取舍：**账号不存在时不计数**。如果不存在也计，攻击者用"真实用户名 + 错误密码"就能把正常账号锁死，防破解的机制本身变成攻击面；账号探测改由接口级 IP 限流兜。
- 锁定与禁用的提示语都用同一句"账号被锁定"，不告诉调用方到底是被谁锁的。
- 前面的部署拓扑如果有可信代理（Nginx/SLB），做法是在代理层配置可信跳数，然后改这里读 `X-Forwarded-For` 的倒数第 N 段 —— 顺序很重要：**先声明哪些代理可信，再去信那个头**，否则等于把限流开关交给客户端。
- **Redis 抖动时 fail-open**：脚本执行异常就记 error 并放行。限流是增强校验不是鉴权本身，fail-closed 等于"Redis 一抖，下单和登录全部 500"；与令牌黑名单、营业状态回源同一口径。

### 3.19 链路追踪 traceId（MDC）

- `TraceIdFilter` 在请求最前面生成/透传 `traceId` 并 `MDC.put`，响应头回传 `X-Trace-Id`，`finally` 里 `MDC.clear()`。
- 日志 pattern 加 `%X{traceId}`，**一次请求的所有日志共用同一个 traceId**。
- 价值：线上排障时 `grep 一个 traceId` 就能还原整条调用链，不用靠时间戳猜。
- **异步与消息线程也要接上**：MDC 是 ThreadLocal，任务丢进线程池就断了。所以 `mqSendExecutor` 配了一个 `TaskDecorator`，在提交时把调用线程的 MDC 快照复制进任务线程、执行完再清掉——"事务提交后异步发送"这一步的日志仍然挂在同一条 traceId 上。消费侧同理：Rabbit 用消息头、Kafka 用 record header 把 traceId 传过去，`MessageTrace.restore` 在消费者线程里重建，`finally` 里 clear（线程池复用不 clear 会串号）。

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
- **生产者**：RabbitMQ 开 `publisher-confirm` + `publisher-return`；Kafka 设 `acks=all` + 幂等生产者。发送侧**先认领再发送**（`where status=0` 条件更新抢占），拿到确认才标已发送，避免两个实例重复发同一条。
- **Broker**：交换机、队列、消息都 **durable 持久化**；Kafka 靠多副本 ISR，topic 启动时显式声明分区与副本数，不靠自动建。
- **消费者**：RabbitMQ **手动 ACK**，失败 nack 且不 requeue → 进死信队列，**死信队列有消费者**把该做的事补做掉；Kafka **处理完再提交 offset**，异常一律交回 `DefaultErrorHandler` 退避重试，用尽落 DLT。

还有两个只在故障场景才暴露的点：**死信消费者必须 ack**（manual ack 下不 ack，消息会在通道关闭时重新入队，死信消费者就无限转圈吞自己刚处理过的消息）；**去重 key 要在失败时撤销**（`setIfAbsent` 占了位但业务写失败，重投会被去重逻辑当"已处理"跳过，这条消息就永久丢了）。

另外，超时取消这类"消息可能到不了"的场景我不只靠一条路径：延迟队列和每分钟的对账扫描**并存**，靠 `where status=1` 的条件更新保证重复执行不出错——多花一次索引扫描，换来 MQ 挂掉时订单不会永久停在待支付。」

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
- 缺点：**一旦签发，过期前无法主动失效**。我做了两条互补的路径：
  - **登出**（能拿到令牌原文）→ Redis 黑名单，key 存令牌的 **SHA-256**（不把仍可用的凭证原样落 Redis），TTL 取配置的存活时间。
  - **禁用账号**（拿不到对方令牌）→ 按员工记一个"作废时间点" `jwt:valid-after:{empId}`，拦截器比对令牌的 `iat`，早于它签发的一律 401。这就是"token 版本号"那一类做法，代价是每次请求多一次 Redis GET。
- 两条增强校验都是 **fail-open**（Redis 抖动时放行 + error 日志）：它们不是鉴权本身，签名校验仍然执行；fail-closed 会让一次 Redis 抖动把全站变 401。真正要更强一致性就得每次回查 DB 的用户状态，那是延迟与正确性的取舍。
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
- **验签 ≠ 金额正确**：验签只证明"消息来自微信"，不证明"金额对得上"。所以入账前比对回调 `amount.total` 与订单应付，用同一套元转分口径（`movePointRight(2)` + 精确取整），只接受 `trade_state=SUCCESS`。
- **幂等**：微信会**重复推送**直到收到 SUCCESS。改状态用条件更新 `where number=? and pay_status=0 and status=1`，**只有一个请求能改成功**，不靠先查再改，也不需要额外的锁。
- **落空不等于无事发生**：影响 0 行还要分辨为什么 —— 已入账的重复回调正常返回；但"订单已被超时取消却收到成功支付"是收了钱不认账，我会把支付状态留痕（`pay_status 0→1`，订单状态仍是已取消）并**立刻发起全额退款**，退款本身再由 `order_refund` + 补偿任务兜底。
- **响应**：处理成功返回 200 + `{code:"SUCCESS"}`；**处理失败必须返回非 200 + FAIL**，否则微信认为已送达并停止重试，订单会永久停在未支付。退款回调同理，金额对不上就拒绝确认并回 FAIL。
- **回调地址必须是公网 HTTPS**，本地开发用内网穿透（项目配的是 cpolar 域名）；`refundNotifyUrl` 现在有对应的 `/notify/refundSuccess` 端点接结果。

### Q15：WebSocket 集群下怎么推送？

「session 存**单机内存**，多实例时管理端连 A 节点、支付回调打在 B 节点就推不到。我改成 **Redis Pub/Sub 广播**：业务侧 `publish` 到频道，每个节点通过 `MessageListener` 订阅，收到后推给**自己本地持有的 session**。因为 **session 不可序列化、不能存 Redis**，所以正确做法不是共享 session，而是**广播消息 + 各节点本地转发**。生产规模更大时可以上 Spring WebSocket + STOMP + RabbitMQ 做 broker。

追问"这条通道谁能连"：**之前谁都能连**——`/ws/{sid}` 的 `sid` 是前端随手写的，任何知道地址的客户端连上就能收到别人店铺的订单号广播。现在握手必须带**管理端 JWT**（`ws://host/ws/{sid}?token=...`），校验两件事：用管理端密钥验签通过、且载荷里有 `empId` 声明。用户端令牌是另一套密钥，签不出这个组合，所以 C 端令牌打不进管理端通道；只验签是不够的，那一步才是把两类令牌分开的判据。

实现上有两个坑：
1. **`@ServerEndpoint` 的实例是容器按连接 new 出来的，不是 Spring Bean**，`@Autowired` 注不进来。所以由一个 `@Component` 的 `WebSocketAuthenticator` 在 `@PostConstruct` 里把自己塞进 `WebSocketServer` 的**静态字段**，`@OnOpen` 再回调它；鉴权器还没注册上（装配失败）时**一律拒绝**——fail-closed 不能让成为 fail-open。
2. **重连时的会话摘除要用 `remove(key, value)`**：同一 `sid` 重连，旧连接的 `@OnClose` 可能晚于新连接的 `@OnOpen` 到达，只按 `sid` 删会把刚建好的新会话摘掉，现象是"页面明明连着却收不到推送"。
」

### Q16：订单列表有没有 N+1 问题？

「原来有两处，现在都改成了**批量查 + 内存分组**：
- 订单列表：先分页查订单，再**循环里逐条查订单明细**，10 条就是 1+10 次。改成一次 `where order_id in (...)` 批量查回来，`stream().groupingBy(orderId)` 归位，从 N+1 降到 2 次查询。
- C 端菜单的"菜品+口味"：`listWithFlavor` 原来是查 N 个菜品然后**每个菜品单查一次口味**，一个分类 30 个菜就是 31 条 SQL，而这是最热的一条读路径。同样改成 `where dish_id in (...)` + `groupingBy(dishId)`，并且有用例锁住"只查一次"。

这两处原来都靠"反正有缓存挡着"糊过去，但缓存只挡住命中路径：key 过期那一次、以及管理端那些不走这个缓存的查询，照样是 N+1。所以我的口径是**查询次数本身要修，缓存是另一件事**。

更进一步可以 SQL join 后用 `resultMap` 的 `collection` 一次查完，但分页场景下 join 会让 `count` 变成明细行数，PageHelper 的分页总数就错了——所以我停在"两条 SQL"而不是"一条大 join"。」

### Q17：缓存穿透 / 击穿 / 雪崩？

- **穿透**（查不存在的 key，每次都打 DB）：本项目用**缓存空值 + TTL**（`dishCache` 连空列表一起缓存），所以按不存在的分类 id 刷接口打不到数据库；量大时再上布隆过滤器。
- **击穿**（热点 key 过期那一瞬间全打 DB）：**`@Cacheable(sync = true)` + `RedisCacheWriter.lockingRedisCacheWriter`**。同一时刻只有一个线程抢到互斥、回源并写回，其余线程等它的结果。这里有个实现细节：`sync=true` 只有配 locking 写器才有效，配默认的 non-locking 写器 Spring 会直接拒绝启动。
- **雪崩**（大批 key 同时过期）：**TTL 随机抖动**。我自己写了一个 `JitteredTtlRedisCacheWriter` 包在真写器外面，每个 key 的过期时间在基准值 **±20%** 里浮动（60 分钟 → 48~72 分钟）。固定 TTL 本质上是自己造了一次雪崩：这一批 key 是同一秒写进去的，就会同一秒集体过期。
- 包装类只改 TTL，`get/putIfAbsent/remove/clean/统计收集` 全部原样委托 —— 其中 `putIfAbsent` 的**返回值必须透传**，不然 `sync=true` 的互斥就废了；`withStatisticsCollector` 要落到真写器上，不然命中率统计恒为 0。
- 本项目 Redis 用途：营业状态、分布式锁、消费去重、令牌黑名单、限流窗口、**菜品/套餐缓存**。缓存一致性用的是 **Cache Aside + 事务提交后删缓存**（`TransactionAwareCacheManagerProxy`），写操作一律**删缓存而不是更新缓存**。下一步可以补的是热点 key 的**逻辑过期**（不删、后台异步重建，把"等一个线程回源"变成"读到稍旧的值但不阻塞"）和**延迟双删**。

### Q18：接口幂等怎么设计？

「三种常用：
1. **Token 机制**：进入页面先拿一个 token，提交时带上，服务端 `del token` 成功才处理（防重复提交）；
2. **唯一索引**：业务唯一键建 unique，靠 DB 兜底。本项目里最能说明问题的是**退款**：`refund_no = "RF" + 订单号` 建唯一索引 + `insert ... on duplicate key update id=id`，重复点击、消息重投、并发补偿全部撞在同一个键上。**关键是不靠"调用前先查一次"** —— 查和写之间有竞态，两个线程都能查到"还没退过"，然后就退两次；
3. **状态机幂等**：`update ... where status=前置状态`，本项目取消、接单、派送、完成、支付入账、退款到账全是这个套路，改 0 行就说明状态不对。支付回调的 `where pay_status=0 and status=1` 就是它 —— 重复推送只有一个能改成功，不需要额外加锁。」

补一句取舍：**幂等要落在"写"上，不要落在"读+判断"上**；读+判断只能当快速路径，最终一致性由唯一键或条件更新保证。

### Q19：AOP 底层？

「**动态代理**。目标类**实现接口**用 **JDK Proxy**；**没实现接口**用 **CGLIB**（生成子类，所以不能代理 final 类和 final 方法）。SpringBoot 2.x 默认 `proxyTargetClass=true`，**统一走 CGLIB**。我们公共字段填充是 `@Before` 前置通知，作用在 Mapper 上，由 Spring AOP 生成代理对象织入。」

### Q20：过滤器、拦截器、切面区别？

「**Filter**（Servlet 规范，能拿到原始 request/response，最外层，乱码、跨域）→ **Interceptor**（Spring MVC，能拿到 Handler 方法，做登录校验、权限）→ **AOP**（方法级，粒度最细，能对任意 Bean 的方法织入，做日志、事务、自动填充）。」

### Q21：项目里 `allow-circular-references: true` 是干嘛的？

「SpringBoot 2.6 起**默认禁止循环依赖**，这个配置是把它放开。出现循环依赖说明**代码分层有问题**，正确做法是**重构**（抽取第三方类、或用 `@Lazy` 延迟注入、或 setter 注入），别靠配置兜着。顺带可以讲三级缓存：`singletonObjects` / `earlySingletonObjects` / `singletonFactories`，三级缓存解决的是**setter 注入的循环依赖**，构造器注入的循环依赖解决不了。」

### Q22：SpringBoot 自动配置原理？

「`@SpringBootApplication` 里的 `@EnableAutoConfiguration` → 加载 `META-INF/spring.factories`（2.7 也开始支持 `AutoConfiguration.imports`）里的配置类 → 靠 `@ConditionalOnXxx`（`@ConditionalOnClass`、`@ConditionalOnMissingBean`、`@ConditionalOnProperty`）**按条件决定是否装配** → 用户自己定义的 Bean 优先（`@ConditionalOnMissingBean`）。我项目里 MQ 的开关 `@ConditionalOnProperty` 就是这个机制的实际应用。」

### Q23：Dockerfile 多阶段构建有什么用？

「两个好处：**减小镜像体积** + **分离构建和运行环境**。第一阶段用带 Maven、JDK、源码的 `builder` 镜像编译打包；第二阶段只 COPY 编译好的 Jar，用只带 JRE 的镜像运行。构建工具、源码、编译中间产物**都不进最终镜像**，镜像从几百 MB 缩到 200MB 左右。另外第一阶段**先 COPY pom 再 go-offline**，能吃到 Docker 层缓存——依赖没变时重复构建不用重新下载依赖。」

### Q24：`depends_on` 和 `condition: service_healthy` 有什么区别？

「`depends_on` 只保证**容器启动顺序**，但容器起来 ≠ 服务就绪——MySQL 容器启动了可能还在初始化，此时应用连库必失败。`condition: service_healthy` 配合容器的 `healthcheck`，会**等到依赖服务真正健康**（MySQL 能 `mysqladmin ping` 通、Redis 能 `ping` 通 PONG）才启动应用，解决启动竞态。我用的是后者。」

### Q25：数据卷和 bind mount 区别？你的项目怎么用的？

「**bind mount** 把宿主机目录挂进容器（我挂 `mq_message.sql` 到 MySQL 初始化目录，就是这个）；**数据卷（volume）** 由 Docker 管理、存在宿主机的 `/var/lib/docker/volumes` 下，**删容器数据还在**，跨容器共享和备份更方便。我的 MySQL/Redis/Kafka/RabbitMQ 数据都用命名卷，重启、重建容器都不丢数据。」

### Q26：容器之间怎么互相访问？

「同一 compose 网络里的容器**直接用服务名当主机名**访问，不用 IP。比如 `application-docker.yml` 里 MySQL 写 `host: mysql`、Redis 写 `host: redis`，RabbitMQ 写 `host: rabbitmq`——因为 compose 会建一个默认网络，并给每个服务做 DNS 解析。而宿主机/本地开发才用 `localhost`，所以我分了 `dev` 和 `docker` 两个 profile 用不同的地址。」

### Q27：限流怎么实现的？几种算法？

「我用的是 **AOP 切面 + Redis 滑动窗口**：自定义 `@RateLimit` 注解，切面里跑一段 Lua 完成"清理窗口外记录 → 计数 → 写入 → 续期"，超了就抛 `RateLimitException`，由全局异常处理器返回 **HTTP 429 + `Retry-After`**。

**计数维度是关键**：已登录按用户 id，**未登录按客户端 IP**，而且只取 `getRemoteAddr()` —— `X-Forwarded-For` 是客户端可自填的头，拿它当维度等于把限流开关交给攻击者（前面有可信代理时应该按可信跳数取）。原来未登录全归进一个 `anon` 桶，一个 NAT 出口就能让所有真实用户一起被限。

罩住的接口：下单（10s/5 次）、催单（10s/3 次）、**管理端登录（60s/10 次）**、**小程序登录（60s/30 次，它每次都要外呼微信）**。

限流只挡频率，挡不住"慢慢试"，所以另加一层**账号维度失败计数**：15 分钟内 5 次密码错误就把账号临时锁定，登录成功清零，计数与锁定键的写入在同一段 Lua 里原子完成。**账号不存在时不计数** —— 否则攻击者用"真实用户名 + 错误密码"就能把正常账号锁死，防破解的机制本身变成攻击面。

四种算法我清楚取舍：

| 算法 | 原理 | 缺点 |
|---|---|---|
| 计数器（固定窗口） | 窗口内计数 | **临界突刺**：窗口边界两秒可能放行 2 倍流量 |
| 滑动窗口 | 把窗口再切分/记录时间戳 | 更平滑，但内存占用更高 |
| 漏桶 | 固定速率流出 | 强制匀速，突发会被削平 |
| 令牌桶 | 固定速率放令牌，攒桶应对突发 | **最常用**，允许一定突发 |

**我这版用的是滑动窗口（Redis ZSET + 一段 Lua）**：member 是请求唯一标识、score 是到达毫秒，脚本里依次做「`ZREMRANGEBYSCORE` 清窗口外 → `ZCARD` 计数 → 未满就 `ZADD` + `PEXPIRE` → 已满就返回最早那条滑出窗口还需要等待的毫秒数」。换掉固定窗口的原因就是上面那行缺点：边界能放过 2 倍流量，对下单接口等于"限流形同虚设的一瞬间"。三个我踩过的实现细节：

1. **清理、读、写、续期必须在一段 Lua 里**：拆开就有竞态，两条线程各自读到"还差一个"就都放行。Redis 单线程执行脚本，天然原子。
2. **ZSET 一定要挂 `PEXPIRE`**：不然被刷过一次的限流 key 会永久留在 Redis 里，等于给自己制造内存泄漏。
3. **等待时间让脚本顺手算出来**，异常带着它走，全局异常处理器写 **HTTP 429 + `Retry-After`**。之前它只是 `BaseException` 的一种，返回 `200 + code=0`——按 HTTP 状态分支的客户端拦截器会把"被限流"当成功，重试也完全没有等待依据。限流的语义本来就是"请求太多"，就该用 429 表达。

再往上扩就是网关层（Sentinel / Gateway）做统一限流，业务侧只保留按接口的细粒度规则；令牌桶留给"允许突发但要匀速落到下游"的场景（比如往第三方批量推送），我这层不需要突发整形所以选了滑动窗口。

还有一个降级口径：**Redis 抖动时限流是 fail-open**——脚本执行抛异常就记 error 并放行。限流是增强校验不是鉴权本身，让它 fail-closed 等于"Redis 一抖，下单和登录全部 500"，比被刷更糟；这和令牌黑名单、营业状态回源用的是同一套口径，也有用例锁住。」

### Q28：线上出问题怎么快速定位？

「靠 **traceId**。我在请求最前面的 `Filter` 里给每个请求生成/透传 traceId 写进 `MDC`，日志 pattern 带 `%X{traceId}`，响应头也回传 `X-Trace-Id`。所以一条请求从进来到 Controller、Service、Mapper 的所有日志都带同一个 traceId，**grep 一个 traceId 就能还原整条调用链**。再配合 `X-Trace-Id` 头，前端把 traceId 反馈回来，就能定位到具体那次请求。MDC 用完必须 `finally` 里 clear，否则线程池复用会串号。」

---

## 5. MQ 改造 · 我做的亮点（主动抛出来加分）

**改造前**：`OrderTask` 每分钟扫表取消超时订单。

**改造后**：

1. **RabbitMQ TTL + 死信队列**做订单维度延迟取消，**精确到秒、不扫表、消息持久化**；消费端**条件更新幂等**；**死信队列配了消费者**，坏消息进去之后仍然会被补做，而不是停在队列里等人发现。
2. **Spring 事件解耦**：下单只 `publishEvent`，由 `@TransactionalEventListener(AFTER_COMMIT)` 在**事务提交后**再投递 MQ —— **彻底解决"事务回滚但消息已发出"**的脏消息问题；而"提交后"这一步本身是**丢进独立线程池**执行的，HTTP 线程不会站在已提交的事务外面等 broker 确认（`waitForConfirms` 最长 5 秒）。线程池饱和被拒绝也只是"这条消息等下一个补偿周期"，不会影响下单正确性。
3. **Kafka** 承接订单创建事件做异步统计，**主链路瘦身**，下单响应更快；消费失败不吞消息，退避重试用尽落 `order.created.DLT`。
4. **双开关降级**（`sky.messaging.kafka.enabled` / `sky.messaging.rabbitmq.enabled`），本地开发不装中间件也能跑；MQ 关闭时**自动回落定时任务兜底**，MQ 开启时兜底扫描**仍然并存**做对账 —— 因为"MQ 开着"不等于"每条消息都被消费成功"。
5. **Kafka 侧 Redis SETNX 去重**，保证重复消费不影响统计结果；写失败时**先撤销去重 key 再抛异常**，否则重投会被当重复吞掉。
6. **发送锁按通道拆开**：Rabbit 和 Kafka 各一把锁、各取自己那批待发消息。原来一把全局锁罩住两个通道，Rabbit 卡住等确认时 Kafka 也在排队，而且所有实例抢同一把锁，水平扩容对发送吞吐无效。

讲完补一句：「如果只用一种中间件，我会选 **RocketMQ**，延迟消息 + 事务消息 + 高可靠一套搞定；现在双 MQ 是按场景分工——**RabbitMQ 管延迟，Kafka 管事件流**。」

---

## 6. 诚实清单：已知不足（被问"有什么可以改进"时主动说）

| 问题 | 现状 | 改进方向 |
|---|---|---|
| ~~订单号重复~~ | 已修复 | ✅ 雪花算法 `SnowflakeIdGenerator` |
| ~~WebSocket 单机~~ | 已修复 | ✅ Redis Pub/Sub 广播 |
| ~~支付回调用户身份~~ | 已修复 | ✅ 按 `orderNumber` 查询，不依赖登录态 |
| ~~菜品查询未缓存~~ | 已修复 | ✅ Spring Cache + Cache Aside + 事务提交后删缓存 |
| ~~N+1 查询~~ | 已修复 | ✅ 用户端与管理端列表都改成 `where order_id in (...)` 批量查 + 内存分组 |
| ~~密码 MD5~~ | 已修复 | ✅ BCrypt；库里遗留的 32 位 MD5 在首次登录校验通过后就地重写 |
| ~~金额信任前端~~ | 已修复 | ✅ 请求 DTO 删掉金额字段，服务端按购物车明细重算 |
| ~~越权（IDOR）~~ | 已修复 | ✅ 订单/地址簿/购物车全部按当前登录用户 id 过滤 |
| ~~打烊只改个展示字段~~ | 已修复 | ✅ 状态落库 + Redis 缓存回源，加购与下单都拦 |
| ~~加购后下架照样成单、沿用历史价~~ | 已修复 | ✅ 下单前一次批量复核在售与现价，不可售整单拒绝并点名商品 |
| ~~打包费由客户端填~~ | 已修复 | ✅ DTO 删字段，服务端按 `单价 × 份数` 计算并封顶 |
| ~~已取消的单还能发起支付；迟到回调不退钱~~ | 已修复 | ✅ 发起支付校验 `status=1`；回调落空时判定迟到支付 → 留痕 + 自动全额退款 |
| ~~退款失败只有一行日志~~ | 已修复 | ✅ `order_refund` 表 + 唯一退款单号防二次退款 + 补偿任务退避重投 + 退款回调端点 |
| ~~接单/拒单/派送/完成对用户静默~~ | 已修复 | ✅ `order_notification` 站内通知，含拒单原因与超时取消，三个用户端接口 |
| ~~禁用员工后旧令牌还能用满 TTL~~ | 已修复 | ✅ 按员工记作废时间点，拦截器比对 `iat`；同时禁止自我禁用 |
| ~~登录口无限流无锁定~~ | 已修复 | ✅ 未登录按 IP 分桶限流 + 账号失败计数 5 次锁 15 分钟 |
| ~~限流触发返回 `200 + code=0`~~ | 已修复 | ✅ 滑动窗口 + **HTTP 429 + `Retry-After`**（等待时长由 Lua 顺手算出来） |
| ~~固定窗口边界可放 2 倍流量~~ | 已修复 | ✅ Redis ZSET 滑动窗口，清理/计数/写入/续期在一段 Lua 里原子完成 |
| ~~缓存只有 TTL，没有互斥重建~~ | 已修复 | ✅ `@Cacheable(sync=true)` + `lockingRedisCacheWriter` 防击穿；写器外包一层 **±20% TTL 抖动** 防雪崩 |
| ~~死信队列没有消费者，MQ 开着时兜底任务还不创建~~ | 已修复 | ✅ DLQ 消费者补做取消并通知、无论成败都 ack；`OrderTimeoutFallbackTask` 与 MQ **并存**做对账 |
| ~~`AFTER_COMMIT` 里同步等 broker 确认~~ | 已修复 | ✅ 提交后丢进独立线程池（有界队列 + 优雅停机 + MDC 传递），饱和就交给补偿任务 |
| ~~Kafka 统计消费 catch 后 offset 照常提交~~ | 已修复 | ✅ 异常交回 `DefaultErrorHandler` 退避重试，用尽落 `order.created.DLT`；坏报文列不可重试；写失败先撤去重 key |
| ~~所有实例抢一把全局发送锁~~ | 已修复 | ✅ 锁按通道拆分（Rabbit / Kafka 各一把），各自只取自己那批待发消息 |
| ~~报表半开边界、Top10 按名称分组~~ | 已修复 | ✅ 区间统一 `[begin 00:00, end+1 00:00)`；Top10 按 SKU id 分组 |
| ~~逐日循环查询、导出 120+ 次查询~~ | 已修复 | ✅ 两条 `group by date(...)` 聚合 + 起点存量累加；缺日在内存补 0 |
| ~~派送超时按下单时间判定、阈值硬编码~~ | 已修复 | ✅ 新增 `delivery_start_time`（V4）作为判定基准，阈值 `auto-complete-after-minutes` 可配 |
| ~~员工列表回传 BCrypt 哈希与身份证；DTO 整体打进日志~~ | 已修复 | ✅ 列表 SQL 显式列不含这两项；`@ToString.Exclude` + 日志只留 id/username |
| ~~OSS 对象只上传不回收~~ | 已修复 | ✅ `media_object` 台账 + 四张表引用计数 + 保留期 180 天后批量物理删除 |
| ~~`/ws/{sid}` 谁都能连，收到别人店铺的订单广播~~ | 已修复 | ✅ 握手必须带管理端 JWT（验签 + `empId`），失败即 close，鉴权器未注册时 fail-closed |
| ~~C 端菜单菜品+口味 N+1~~ | 已修复 | ✅ `where dish_id in (...)` 批量查 + `groupingBy`，有用例锁住"只查一次" |
| ~~导出没有 `Content-Disposition`~~ | 已修复 | ✅ xlsx `Content-Type` + RFC 5987 双 filename（ASCII 回退 + `filename*`） |
| 循环依赖 | 配置层面放开（`allow-circular-references: true`） | 重构分层 + ArchUnit 守边界 |
| 无库存扣减 | 只有菜品起停售 | 高并发场景加库存 + 乐观锁 |
| 金额精度 | 营业额统计仍是 `Double` | 全链路 `BigDecimal` |
| Kafka 死信的处理入口 | `order.created.DLT` 已声明并接住重试耗尽的消息 | 补 DLT 重放/告警入口（与 Rabbit 死信已有消费者的状态对齐） |
| RBAC / 优惠券 / 起送价 | 员工表没有 role 字段，营销整套没做 | 独立一层：权限模型 + 营销规则引擎 |
| 大版本 | Spring Boot 2.7.3 已 EOL | 3.x + jakarta 迁移 + Java 21 虚拟线程 |

**主动说比被问出来强十倍**——说出改进方案，说明你清楚问题在哪。

---

## 6.5 第三批改造（A/B/C，可直接开口背）

**A 档 —— 五个能直接资损的洞**

1. 「我下单最初是 `BeanUtils.copyProperties` 把前端 DTO 拷进订单表，DTO 里就有 `amount`，等于**客户端自己填应付金额**。我把金额字段从 DTO 删掉，改成服务端按购物车明细 Σ(单价×数量)+打包费重算。这个问题的根因不是漏了个校验，而是**反射式属性拷贝会把"字段名一样"这件事当成业务语义**，所以我换成显式赋值。」
2. 「订单详情、取消、催单、地址簿的查改删都只按 id 查，没有归属校验，遍历 id 就能读到别人的订单和收货地址。修法是**所有 C 端读写都带上 `user_id` 条件**，而且"不存在"和"不属于你"统一回同一个提示，不帮攻击者探测。」
3. 「管理端取消订单不校验状态，已完成/已取消的单还能再取消并再次退款；而且原来**先退款再改状态、中间没事务**。我改成先做带前置状态的条件更新把订单落成已取消+退款态，再去调微信退款，退款失败只告警不回滚 —— 因为状态重复变更会造成二次退款，而退款缺失可以人工补偿。」
4. 「支付回调只验签不校验金额：验签只能证明**消息来自微信**，不能证明**金额对得上**，付 1 分的回调照样能把 50 元的单置成已支付。现在比对回调 `amount.total` 与订单应付（同一套元转分口径），并且只接受 `trade_state=SUCCESS`。」
5. 「报表接口 `while(!begin.equals(end))` 展开日期，只要开始日期晚于结束日期就永不退出，一个参数就能把服务打挂。我把三处统一成一个带校验的日期展开：空值、倒序、跨度超 366 天直接拒绝 —— 而**根因是全项目一处参数校验都没有**，所以顺手补了 Bean Validation。」

**B 档 —— 技术栈其实停在 2022 年**

- 「最有意思的一条：我 pom 里**没写 `java.version`**，Boot 2.7 父工程默认 1.8，所以我 Dockerfile 换成 JDK 17 只是让 Lombok 能编译，**产物实测是 major=52 的 Java 8 字节码**。加上 `java.version=17` 之后实测 major=61，CI 里我专门加了一步断言这个数，防止再退回去。」
- 「sky-pojo 里把 `jackson-databind` 硬锁在 **2.9.2**，这是 2017 年 `@class` 反序列化 RCE 那一串 CVE 的版本，而且这个 override 会顺着依赖树把整个应用的 Jackson 拉旧。删掉锁、交给 Boot 管理就够了。」
- 「fastjson 1.2.76 我**直接移除依赖**而不是升到 1.2.83：项目里解析的正是微信回调、MQ 报文这类外部可控内容，与其长期盯 AutoType 的版本号，不如统一到已经是主力的 Jackson。」
- 「jjwt 0.9.1 换 0.12.6 之后 HS256 强制要求密钥 ≥256bit，`itcast` 这种默认值直接起不来 —— 这是好事：**我把默认值删了**，环境变量缺失就启动失败，而不是静默用一个可被字典穷举的弱密钥。」
- 「logout 原来是空实现，JWT 无状态、服务端删不掉令牌，2 小时有效期内一直能用。我加了 Redis 黑名单：key 存令牌的 SHA-256（不把仍可用的凭证原样落到 Redis），TTL 取配置的存活时间，拦截器多查一次。」

**C 档 —— 上一批"闭环"其实只走了一半**

- 「本地消息表我原来 `doSend` 一返回就标"已发送"，但 Kafka 的 `send()` 是异步的、Rabbit 也没开 publisher confirm —— 标成功只代表**进了本机缓冲区**。现在 Rabbit 用 `invoke + waitForConfirms`，Kafka 用 `send(...).get(5s)`，拿到 broker 确认才落成功。」
- 「`listPending` 是普通 select，三个实例扫到同一批就投三遍。我加了 `status=发送中` 的**条件更新做认领**，只有一个实例能抢到；再加一个 5 分钟超时回收，防止持有者进程崩溃后那条消息永远卡在发送中。」
- 「Rabbit 之前默认 AUTO ack，脏消息抛异常会无限 requeue。改成手动 ack，异常一律 `basicNack(requeue=false)` 落死信队列 —— 死信队列必须接告警，否则只是把堆积换了个地方。」
- 「traceId 原来只活在 Tomcat 线程的 MDC 里，消息一进 MQ 链路就断了。现在生产端把 traceId 写进消息头，消费端取出来放回 MDC，一次下单从接口到消费者能用同一个 traceId 串起来。」
- 「仓库里**根本没有建表脚本**，`docker compose up` 起来是个空库，README 还写着"请自行导入表结构"。我按实体和 Mapper 逐列补了 12 张表的 Flyway 迁移，加了 `orders.number` 等唯一索引和 `(status, order_time)` 组合索引；存量库另给一份手工脚本，因为 `CREATE TABLE IF NOT EXISTS` 不会给已有表补索引。」
- 「测试原来只有三个带 `main` 和 `System.out` 的"假测试"，其中一个把 `@SpringBootTest` 注释掉了却留着 `@Test`，跑 `mvn test` 必然 NPE，所以 CI 只能 `-DskipTests`。现在 28 条断言用例全绿，覆盖算价、越权、回调幂等、先落库再退款、日期守卫、BCrypt、元转分。」

**一句话收尾**

「这批给我的教训是：**"引入了组件"和"把组件的失效场景补上"是两件事**。本地消息表要配认领和送达确认，手动 ack 要配死信和告警，雪花算法要配唯一索引和 workerId 下发，Flyway 要配存量库的 baseline 策略 —— 少一样，可靠性就只是文档上的。」

---

## 6.6 第四批改造：业务闭环（可开口背的短答库）

这一批没有新组件，全是"功能看着有、追问第二层就露底"的业务缺口。按主题分组，每条 2~3 句。

**营业状态**

- 「营业状态原来只写 Redis，读不到就返回"营业中"，而且下单链路根本不查它 —— 打烊照样收单。现在以单行表 `shop_status` 为准，Redis 只是 10 分钟缓存，读不到回源 DB；加购和下单都先判状态，打烊直接业务异常。」
- 「判空顺序是刻意的：**缓存缺失回源数据库，而不是回源一个默认值**。状态这种"要不要收钱"的开关，默认值猜错的方向是资损。」

**下架与改价**

- 「加购时校验在售，但购物车存的是名称+价格快照，商家中途下架或改价就失效了。我在下单前加了一次复核：把购物车里的 id 去重后一次 `in` 批量查回来，校验状态并用**当前价格**重算，任何一行不可售就整单拒绝并点名商品。」
- 「拒绝而不是跳过：静默少收一件商品，用户看到的金额和他以为点的东西就不一致了。同理，删除菜品/套餐时会在同一个事务里清掉所有购物车里指向它的悬空行。」

**打包费**

- 「订单总额我上一批改成服务端算了，但打包费还是前端传 0~100 随便填 —— 等于留了个能改价的口子。现在 DTO 里连这个字段都没了，服务端按 `配置单价 × 总份数` 算，并受上限封顶，`pack_amount` 列也从 INT 改成 DECIMAL(10,2)。」

**支付与取消的竞态**

- 「超时任务取消订单只改 `status=6`，`pay_status` 还是 0。原来发起支付只看 `pay_status`，所以能给一张已取消的订单付钱；付成功之后回调做条件更新影响 0 行，代码只打了一行 warn —— 钱进来了、单没承认、也不退。现在发起支付要求 `status=1`，回调落空时如果发现正是"已取消未入账"，就把支付状态留痕并立即发起全额退款。」
- 「这里幂等靠的还是条件更新：`where number=? and status=6 and pay_status=0`，重复回调只有一个能改成功，不会退两次。」

**退款闭环**

- 「退款是异步的：申请 → 微信受理 → 回调确认。原来只有一次 HTTP 调用和一行的日志，没有中间态可查，也没有端点接收退款结果。现在加了 `order_refund` 表和五态状态机，`RefundCompensator` 每分钟带分布式锁重投，退避到 120 分钟、8 次封顶转人工。」
- 「防二次退款不靠"调用前先查一次"，因为查和写之间有竞态；靠 **`refund_no = "RF"+订单号` 的唯一索引** + `insert ... on duplicate key update id=id`，重复申请、消息重投、并发补偿都撞在同一个键上。」
- 「申请退款那段我没加 `@Transactional`：它是一次外部 HTTP 调用，包进事务会把数据库连接按住到微信响应为止，微信慢的时候连接池会被拖空。正确姿势是记录先独立落库、结果再独立更新，崩溃了由补偿任务捡起来。」
- 「退款回调必须核金额：`amount.refund` 和申请值不一致我就拒绝确认并回 FAIL 让微信重试 —— 宁可重试，也不能先告诉用户"已到账"。」

**订单通知**

- 「接单、拒单、派送、完成以前对用户体验上是静默的，只有支付和催单走了 WebSocket 广播。我没有把 WebSocket 改成定向推送，因为 `@ServerEndpoint("/ws/{sid}")` 的 sid 是前端随机生成、没跟用户绑定，做不了"只推给下单那个人"；小程序侧长连接本身也不稳。所以改成站内通知表，每个状态变更写一行，用户端三个接口拉取。」
- 「通知写入是 best-effort：包 try/catch 打 error。业务状态此时已经落库，不能因为一条通知失败把改状态或退款回滚掉。」

**再来一单**

- 「原来把订单明细原样拷进购物车，四个问题：已下架的也搬进来、沿用成交时的历史价、跟购物车里同款不合并（会撞唯一索引）、不受 99 份上限约束。现在复用同一套在售复核（这里是跳过而不是一律拒绝，否则一个菜下架会让按钮完全点不动），价格取现价，按 SKU 合并、数量累加和上限都写在 SQL 里判断。」

**账号安全**

- 「JWT 无状态，服务端删不掉已签发的令牌。登出我能拿到令牌原文，所以进黑名单（key 存 SHA-256，不把仍可用的凭证原样落 Redis）；**禁用账号时我拿不到对方令牌**，所以按员工记一个"作废时间点"，拦截器比对手的 `iat`，早于它就 401。这样禁用不再要等 2 小时 TTL。」
- 「两种增强校验都是 fail-open：Redis 抖动时放行并打 error。它们不是鉴权本身，签名校验仍然照做；fail-closed 会让一次 Redis 抖动把全站变成 401。」
- 「还有一个业务护栏：**不能禁用当前登录账号** —— 原来点一下自己就出不去了，而且系统里可能没有第二个能启用它的账号。」

**登录防爆破**

- 「限流原来未登录全进一个 `anon` 桶，一个 NAT 出口能让所有真实用户一起被限；登录接口压根没有限流，撞库没有成本。现在未登录按客户端 IP 分桶（只取 `getRemoteAddr()`，`X-Forwarded-For` 是客户端可自填的头，用它当维度等于把限流开关交给攻击者），管理端登录 60 秒 10 次，再加账号维度失败计数：15 分钟内 5 次密码错误就临时锁定。」
- 「一个反直觉的取舍：**账号不存在时不计数**。如果不存在也计，攻击者用"真实用户名 + 错误密码"就能把正常账号锁死，防破解的机制本身变成攻击面；账号探测交给 IP 限流兜。」

**一句话收尾**

「上一批我补的是"组件的失效场景"，这一批补的是**业务规则的失效场景**：加购校验了但下单没复核、金额服务端算了但打包费还在前端填、消息必达做了但退款失败只剩日志、限流做了但登录口没罩住 —— 这些都不是技术选型问题，是**每条业务链路的终点有没有真的被拦住**。」

---

## 6.7 第五批改造：可靠性口径与数据边界（可开口背的短答库）

这批没有新中间件，改的是**口径**：同一个功能，追问"过期了谁挡、这条消息谁来兜、这个数为什么和昨天不一样"时能不能答准。按主题分组，每条 2~3 句。

**缓存击穿与雪崩**

- 「原来只有 TTL 和缓存空值，等于只防了穿透。现在防击穿用 `@Cacheable(sync = true)` 配 `lockingRedisCacheWriter`：热点分类的 key 过期那一瞬间只有一个线程回源，其余线程等它的结果。这里有个容易踩的实现细节——`sync=true` 只有配 locking 写器才成立，配默认的 non-locking 写器 Spring 会直接拒绝启动。」
- 「防雪崩我自己写了一个 `JitteredTtlRedisCacheWriter` 包在真写器外面，把每个 key 的 TTL 在基准值 ±20% 里散开。理由很直接：这批 key 是同一秒写进去的，固定 60 分钟就意味着它们同一秒集体过期，**这是我给自己造的一次雪崩**，不是外部流量造成的。」
- 「包装类我只改 TTL，`get/putIfAbsent/remove/clean/统计` 全部原样委托。其中 `putIfAbsent` 的返回值必须透传，不然 `sync=true` 的互斥语义就废了——这个坑是靠一条 `assertThat(result).isSameAs(existing)` 的用例锁住的。」

**死信队列与双轨兜底**

- 「`order.cancel.dlq` 原来只声明不消费，而兜底扫描的创建条件是 `rabbitmq.enabled=false`。两个条件叠在一起的结果是：**MQ 开着的时候，坏消息进死信就等于订单永久停在待支付，没有任何第二条路径会去看它**——死信从最后一道保险变成了黑洞。现在我给它配了消费者，能解析的用同一个条件更新补做取消并通知用户。`@ConditionalOnProperty` 这类"开关一开另一条路就消失"的装配条件，是我现在写降级逻辑时会专门回头看一眼的地方。」
- 「死信消费者**无论补做成败都要 `basicAck`**。项目配的是 `acknowledge-mode: manual`，不 ack 的消息会在通道关闭时重新入队——我第一版就是这么写的，现象会是死信消费者无限转圈吞自己刚处理过的消息。这一条我有 4 个用例锁住，包括"补做失败也照样 ack"。」
- 「兜底扫描现在和 MQ **并存**，不再互斥。理由是取消本身是 `where status=1` 的条件更新，重复扫同一批订单影响 0 行，代价只有每分钟一次很便宜的索引扫描；而 MQ 正常时少一次扫描换来的是"broker 重启丢消息 / 确认丢失"时唯一的那个出口。」

**发送路径**

- 「`@TransactionalEventListener(AFTER_COMMIT)` 里原来是同步发送：事务已经提交，HTTP 线程却还要站在外面等 broker 确认，Rabbit 的 `waitForConfirms` 最长 5 秒、Kafka 的 `future.get` 同样阻塞——**MQ 一慢，下单接口就跟着慢，而订单本身其实已经成功了**。现在提交后只是把任务丢进一个有界线程池，线程池饱和被拒绝也只是"这条消息等下一个补偿周期"，异常不冒回下单链路。」
- 「异步不会丢消息，因为消息本体是在业务事务里写进 `mq_message` 的，这一层只是"尽早发一次"。所以异步的失败语义是"晚一点发"，不是"发不出去"——这两件事必须分开说清楚，不然就会被当成"你为了性能牺牲了可靠性"。」
- 「发送锁原来是一把全局锁罩住两个通道：Rabbit 卡住等确认时 Kafka 也在排队，而且所有实例抢同一把锁，水平扩容对发送吞吐完全无效。现在按通道拆成两把锁、各取自己那批待发消息。」

**Kafka 消费失败**

- 「统计消费原来 `catch (Exception)` 之后只打日志，offset 照常提交——这条统计就永久丢了，也没人能从日志里把它补回来。现在异常一律交回容器：`DefaultErrorHandler` + 指数退避（1s 起、单次上限 8s、总时长上限 15s），重试用尽由 `DeadLetterPublishingRecoverer` 落 `order.created.DLT`，序列化失败和报文结构错误列进**不可重试清单**直接进死信，因为重试一万次也一样。」
- 「有个只在故障场景才暴露的顺序依赖：`setIfAbsent` 占了去重位、但后面 `increment` 失败了，必须**先把去重 key 删掉再抛**。不删的话重投会被去重逻辑当成"已处理"跳过，这一单统计就永久少算一次——去重标记的成功语义是"这条消息处理完了"，我中途失败就等于没处理完。」

**限流算法与 429**

- 「固定窗口的临界突刺是硬伤：前一窗口末尾 5 条 + 后一窗口开头 5 条，两个窗口各自都没超限，但任意 10 秒里过了 10 条。改成 Redis ZSET 滑动窗口，member 是请求唯一标识、score 是到达毫秒，**清理、计数、写入、续期全在一段 Lua 里**——拆开就有竞态，两条线程各自读到"还差一个"就都放行。」
- 「脚本顺手算出"最早那条记录还要多久滑出窗口"，异常带着这个值走，全局异常处理器写成 **HTTP 429 + `Retry-After`**。之前它是 `BaseException` 的一种，返回 `200 + code=0`——按 HTTP 状态分支的客户端拦截器会把被限流当成功，重试也没有等待依据。」
- 「`X-Forwarded-For` 我故意不信：那是客户端可以自填的头，拿它当限流维度等于把限流开关交给攻击者。真要挂可信代理，顺序是**先在代理层声明哪些跳数可信，再去读 XFF 的倒数第 N 段**。」

**报表口径与查询次数**

- 「两处口径问题：区间边界用 `>` / `<` 配 `LocalTime.MIN/MAX`，**`00:00.000` 整点下单的行被两头排除**，23:59:59.999999 到次日 00:00 之间落库的行不属于任何一天；销量 Top10 按 `order_detail.name` 分组，菜品改名后历史销量断档、两个同名菜品被合并、套餐和单品同名也混在一起。现在统一半开区间 `[begin 00:00, end+1 00:00)`，Top10 按 SKU 的 id 分组。」
- 「所以这批改完**报表数字会比以前略高**——不是多算，是把原来漏掉的整点行捡回来了。这种"改了口径导致数据变化"我会主动写进行为变更清单，不然是对账时才发现。」
- 「查询次数：报表原来是逐日循环，导出 30 天要 120+ 次查询，"总用户数"还每天单独 count 一次全表。改成两条 `group by date(...)` 聚合取回整段、内存里按日期 map 缺日补 0；总用户数是 `countUntil(begin)` 一次拿起点存量，再逐日累加新增——同一个口径，一条查询。」
- 「导出我没有换 SXSSF 流式写：行数固定 30，`XSSFWorkbook` 的内存成本可以忽略，真正的开销在原来每单元格一次查询上，而那个已经靠聚合消掉了。**同样的改动要区分"哪里真的贵"**，不然只是把简单的事做复杂。我做的是把响应头补上：xlsx 的 `Content-Type` + `Content-Disposition: attachment`，中文文件名走 RFC 5987 同时给 ASCII 回退名和 `filename*`，因为之前浏览器会尝试原地渲染、文件名退化成 URL 最后一段。」

**派送超时收尾**

- 「`OrderTask` 原来把"下单超过 60 分钟且还在派送中"的单直接置为已完成。判定基准用错了：商家 40 分钟后才派送，就只剩 20 分钟派送窗口，**用户还没收到餐，系统已经把它算成营业额**。现在 `delivery` 动作写 `delivery_start_time`，扫描条件用 `coalesce(delivery_start_time, order_time)`，历史单不会因为新列为空而永远扫不到；阈值也从硬编码变成可配（默认 120 分钟）。」
- 「状态推进一律是 `where id=? and status=4` 的条件更新。原来"查出来改字段再整行 update"有两个问题：会把用户在这期间改过的字段一起覆盖掉，而且和"用户点完成/商家派送"并发时会互相盖掉对方的状态。」

**个人信息**

- 「员工列表是 `select *`，把 BCrypt 哈希和身份证号整页带回前端——口令散列落到能翻员工列表的人手里，离线爆破只是时间问题。现在列表 SQL 写显式列，不含 `password` 和 `id_number`。」
- 「日志那条线我没有逐行去改调用点，而是给实体和 DTO 的敏感字段加 `@ToString.Exclude`。理由不是"这行日志现在不对"，而是**下一行 `log.info("{}", employee)` 也会漏**——在类型上堵住比在每个调用点小心更可靠。控制器日志只留 id 和用户名。SQL 日志也只在 dev profile 开，因为它的可见范围远大于数据库。」

**图片生命周期**

- 「全项目没用过 `deleteObject`，bucket 只有进没有出。但我**没有**在删除菜品时顺手删图——订单明细里存的就是同一个 URL，删早了用户回看自己的历史订单就是 404 图。做法是上传即登记 `media_object`，商品不再引用时先查菜品/套餐/购物车/订单明细**四张表的引用计数**，都无引用才进"待回收"，再等保留期（默认 180 天）才真的删。」
- 「回收任务删除前会**再确认一次引用**（待回收期间可能有别的商品填了同一个 URL），并用 `update set status=2 where id=? and status=1` **领取删除权**，多实例同时扫到同一行只有一个会真的去调 OSS。`order_detail.image` 建了前缀索引，不然这条 count 在最大的那张表上就是全表扫。」

**WebSocket 握手鉴权**

- 「`/ws/{sid}` 是管理端的来单/催单广播通道，之前谁连上都能收到别人店铺的订单号。现在握手必须带管理端 JWT：`ws://host/ws/{sid}?token=...`，要求**用管理端密钥验签通过且载荷里有 `empId`**——用户端令牌是另一套密钥，只验签不够，`empId` 这个声明才是把两类令牌分开的判据。」
- 「实现上的坑是 `@ServerEndpoint` 的实例由容器按连接 new 出来，不是 Spring Bean，`@Autowired` 注不进来。所以由一个 `@Component` 的鉴权器在 `@PostConstruct` 里把自己塞进静态字段，握手时回调；**鉴权器没注册上时一律拒绝**——装配失败不能变成裸奔。」
- 「顺手修了一个并发 bug：同一 `sid` 重连时，旧连接的 `@OnClose` 可能晚于新连接的 `@OnOpen` 到达，按 `sid` 删会把刚建好的新会话摘掉，现象是"页面连着但收不到推送"。改成 `remove(sid, session)` 按值摘除。」

**一句话收尾**

「这批的共同点是：**功能都在，口径不在**。缓存有 TTL 但过期那一瞬间没人挡、死信队列声明了但没人消费、限流有但算法能被打穿、报表能出数但整点行不属于任何一天、图片能上传但不知道谁在用、WebSocket 能推但谁都能连——这些都不是选型错误，是**每一条路径的失败状态和边界条件有没有被定义过**。所以我现在的自查方式是顺着"如果这一步失败了/如果是 00:00 整点/如果两个实例同时扫到"再走一遍，而不是只看主流程图。」

---

## 7. 踩过的坑（真实，可聊）

1. **Lombok 1.18.20 在 JDK 17/25 下注解处理器失效**，编译报"找不到符号 getXxx()"（其实 `@Data` 根本没生成 getter）。解决：**升到 1.18.30**，并确保 Maven 跑在 **JDK 17** 上（本机默认 JDK 25 也会挂）。同样的坑还出现在 Dockerfile 里——原来写 JDK 8，升完 Lombok 后 docker 构建会失败，得同步改成 JDK 17。
2. **Spring 事件默认是同步的**，在事务内直接 publish 会导致"**事务还没提交，消费者已经查不到订单**"。解决：用 `@TransactionalEventListener`（默认 `AFTER_COMMIT`）。
3. **重复下单**：只靠前端防抖没用，后端必须加**分布式锁 + 状态机校验**。
4. **序列化不开启 default typing**：`new GenericJackson2JsonRedisSerializer(自定义 ObjectMapper)` 反序列化回来是 `LinkedHashMap`，必须手动 `activateDefaultTyping`。
5. **死信消费者写成 `@RabbitListener(String payload, Message message)` 是个坑**：项目配的是 `acknowledge-mode: manual`，这种签名根本拿不到 deliveryTag，消息既不 ack 也不 nack，通道关闭时就重新入队——死信消费者变成无限转圈。必须签名带 `Channel + @Header(DELIVERY_TAG)`，`finally` 里无条件 ack。
6. **Boot 2.7 是 javax 命名空间**：从别处抄来的 `@PostConstruct` 写成 `jakarta.annotation` 会编译报"找不到符号"（或干脆不生效），这个项目里必须用 `javax.annotation.PostConstruct`。
7. **`ExponentialBackOff` 没有 `setMaxAttempts(int)`**：那是 `FixedBackOff` 的 API，我一开始照着写下了编译不过。指数退避的"次数上限"只能靠 `setMaxElapsedTime` 表达（这里给 15s），要精确次数得自己包一层。
8. **`@ServerEndpoint` 里注不进 Spring Bean**：容器按连接 new 实例，不走 Spring 生命周期。要么用静态字段把 Spring 侧的协作者递进去，要么上 `SpringConfigurator`；`@Autowired` 写在那儿只会静默为 null。
9. **Mockito 对基本类型参数用 `any()` 会 NPE**：`notify(Long, Long, int, String, String)` 里第三个是 `int`，`any()` 返回 null、拆箱就炸，而且 matcher 会留在栈上，**下一个测试类莫名其妙报 InvalidUseOfMatchers**。基本类型要用 `anyInt()`/`anyLong()`。

---

## 8. 场景深挖：面试官就着项目往下追问

### 场景 1：把完整下单链路串起来讲

「用户点下单 → 拦截器校验 JWT 拿到 userId → Service **先判门店是否营业**（打烊直接拒，而且这一步在加锁之前，不给无效请求占锁）→ **Redisson 锁**防重复提交 → 校验地址簿必须属于当前用户、购物车非空 → **下单前复核**：把购物车 id 去重后一次 `in` 批量查回商品表，校验仍在售并按**当前价格**重算总额和打包费，任一不可售整单拒绝 → 一个**事务**里插订单、插明细、清购物车、写两条 **mq_message** → 事务提交后 `@TransactionalEventListener` 触发立即发送 → 失败由**补偿任务**重发 → RabbitMQ 延迟队列 15 分钟转死信**取消未支付订单**（同时写一条站内通知告诉用户）、Kafka 异步统计 → 用户支付前再校验**订单仍是待付款** → 微信回调**验签 + 解密 + 核金额** → 条件更新入账（幂等），落空就判定迟到支付并**自动退款** → **Redis Pub/Sub 广播**来单提醒 + 给用户写支付通知 → 管理端 WebSocket 弹窗。

整条链路里：**营业状态管能不能收单，复核管收多少钱，锁管重复提交，事务管一致，消息表管可靠，验签管来源，条件更新管重复推送，唯一退款单号管退两次，站内通知管让用户知道发生了什么**。」

### 场景 2：订单量暴增，哪里先扛不住？

「三处：① **DB**——超时取消原来每分钟扫表（已改 MQ，但保留一条与 MQ 并存的低频对账），剩下的压力是分页查询和报表统计，报表原来按天循环查 30 次、导出要 120+ 次，现在换成两条 `group by date(...)` 聚合；② **Redis**——缓存、锁、限流、去重都靠它，是单点，生产要主从 + 哨兵/集群；③ **消息堆积**——消费慢会堆积，要监控 lag 并扩容消费者。入口再加**限流削峰**（下单/催单/两个登录接口都在 `@RateLimit` 覆盖里，超限回 429 + `Retry-After`）。」

### 场景 3：Redis 挂了怎么办？

「分场景答：**营业状态/菜品缓存** → 降级直接查库，会慢但不挂；**分布式锁** → Redisson 拿不到锁，下单失败——**下单失败好过重复下单**；**限流** → 拿不到窗口计数就放行并打 error（限流是增强，不该因为 Redis 抖动把业务全关）；**令牌黑名单/作废时间点** → fail-open，签名校验仍然执行，否则一次 Redis 抖动就把全站变成 401；**消费去重** → 去重读不到就不累计，宁可少算一次，也不给同一订单写两条统计。根上 Redis 必须主从 + 哨兵/集群，不能单点。」

### 场景 4：MQ 挂了怎么办？

「这正是**本地消息表**的价值：消息已经和订单在同一个事务里落库，MQ 恢复后补偿任务继续重发，**不会丢**；发送是提交后异步触发的，MQ 慢也不会把下单接口的响应拖住。超时取消这条路径还有 `OrderTimeoutFallbackTask` 每分钟对账兜底——它和 MQ **并存**，因为"MQ 开着"不等于"每条消息都被消费成功"。真进了死信，Rabbit 侧有消费者补做取消，Kafka 侧退避重试用尽落 `order.created.DLT`。所以 MQ 挂只会**延迟**，不会**丢消息**、不会**错状态**。」

### 场景 5：改了菜品价格，用户看到旧价怎么办（缓存一致性）

「我用 **Cache Aside**：更新 DB 后**删缓存**，下次读回源。两个边界：**删缓存失败** → 延迟双删 / 重试补偿；**更新和删除之间读到旧值** → 短暂不一致，一般业务可接受，强一致才上读写锁或版本号。我的删缓存挂在**事务提交后**，先保证 DB 正确，再让缓存失效。

但**展示层的缓存不一致和成交层的绝不能混为一谈**，所以我把这条链分成两层：菜单和详情走缓存，看到的旧价无所谓；**下单那一刻不信任何缓存和快照** —— 购物车行里存的是加购时的名称与价格快照，我按 id 批量回查商品表，用**当前价格**重算总额和明细，同时复核在售状态。这样"用户看到 12 元、结算按 10 元收"和"改价后按旧价成交"两个方向都不会发生，最坏只是结算时提示价格变化需要重新确认。」

### 场景 6：分布式锁什么场景会失效？

「① **锁过期**（业务超时）→ Redisson watchdog 续期解决；② **主从切换**（主挂了锁没同步到从）→ 极端用红锁 RedLock，但红锁有争议，别乱用；③ **锁被误删** → 靠持有者校验防误删。核心结论：**锁只保证互斥，最终正确性靠状态机幂等兜底**。」

### 场景 7：你怎么验证这些改动？

「不会"看代码觉得对"。我分了四层：

1. **编译与产物**：`mvn -o clean package`（显式 `JAVA_HOME` 指 JDK 17），并用 `od`/`javap` 量一次 class 的 major version 必须是 61，这条断言也写进了 CI；
2. **单元用例**：现在 **123 条**，全是断言型（Mockito + AssertJ），`Failures: 0, Errors: 0`。这一批我特意让用例去锁"行为"而不是锁"实现"，比如死信消费者"补做失败也照样 ack"、`putIfAbsent` 返回值必须透传、Redis 抖动时限流放行、去重 key 在写失败时被撤销、图片仍被订单明细引用时不回收；
3. **静态核对**：微信验签用 `javap` 核对 SDK 类签名再写；SQL 改动逐条对着 DDL 看列名和索引是否真存在（`group by date(order_time)` 这种就得确认没有踩 ONLY_FULL_GROUP_BY）；
4. **编排**：`docker compose config` 校验语法。

**再往上一层是 Testcontainers 的集成测试**——起真的 MySQL/Redis 跑迁移脚本和 Lua，这是下一步要补的，因为 Flyway 迁移和 Redis Lua 这两类东西的验证结论不该停在"代码看起来对"。」

### 场景 8：项目里最难的 bug

「**Lombok 1.18.20 在 JDK 17 下注解处理器失效**：编译报"找不到符号 getXxx"，看着像业务代码问题，实际是 `@Data` 根本没生成 getter。定位关键是"这个类明明加了 `@Data` 却找不到 getter"——排除法锁定工具链版本，升 Lombok + 统一 JDK 17 解决。这个 bug 教会我：**报错位置不一定是根因**。」

---

## 9. 关键词速查（临考前扫一遍）

`JWT + 拦截器 + ThreadLocal` · `AOP 反射自动填充` · `AOP + Redis + Lua 限流` · `滑动窗口 ZSET + 429 Retry-After` · `限流/黑名单 fail-open` · `MDC traceId` · `RestControllerAdvice` · `PageHelper 拦截器改 SQL` · `MyBatis # 与 $` · `@Transactional 五失效` · `Redisson watchdog 续期` · `RabbitMQ TTL + 死信` · `死信消费者必 ack + 兜底扫描并存` · `本地消息表 + 补偿重发` · `TransactionalEventListener AFTER_COMMIT + 异步线程池` · `发送锁按通道拆分` · `Kafka key 分区有序 + SETNX 幂等` · `DefaultErrorHandler + DLT + 退避` · `去重 key 失败要撤销` · `条件更新幂等 where status=?` · `WebSocket → Redis Pub/Sub 广播` · `握手鉴权：验签 + empId 声明` · `@ServerEndpoint 不是 Spring Bean` · `微信 V3 二次签名 + 验签解密` · `雪花算法订单号` · `POI 模板导出 + RFC 5987 中文文件名` · `缓存三兄弟 + Cache Aside` · `sync=true + locking 写器` · `TTL ±20% 抖动` · `N+1 → in 批量查` · `半开区间 [begin, end+1)` · `group by date() 聚合替代逐日循环` · `Top10 按 SKU id 分组` · `delivery_start_time 判定派送超时` · `PII：显式列 + @ToString.Exclude` · `media_object 引用计数 + 保留期回收` · `多阶段构建 + healthcheck` · `ConditionalOnProperty 条件装配`

5. **pom 漏写 `java.version` 是静默失败**：Boot 父工程默认 1.8，JDK 17 编译出来的仍是 Java 8 字节码，你完全看不出问题。用 `javap -v | grep "major version"` 量一次才算确认（52=Java 8，61=Java 17），并把这条断言写进 CI。
6. **`sky-pojo` 里一个 `jackson-databind` 的版本硬锁会传染全应用**：Boot 管理版本只在没有显式 version 时生效，我删掉那行 2.9.2 之后解析到 2.13.3。
7. **jjwt 升 0.12 不只是换 API**：`Keys.hmacShaKeyFor` 会拒绝长度不足的密钥，所以必须同时删掉 yml 里的弱默认值，否则升级直接变成启动失败。
8. **`@SpringBootTest` 被注释掉但 `@Test` 留着**，字段全是 null，`mvn test` 会 NPE —— 这也是为什么镜像构建一直 `-DskipTests`：测试从没能真正跑过。
