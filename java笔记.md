# 苍穹外卖 Java / Spring Boot 面试知识点（精简完善版）

## 1. 项目架构

```text
客户端
  ↓
Tomcat
  ↓
Filter
  ↓
DispatcherServlet
  ↓
Interceptor
  ↓
Controller
  ↓
AOP Proxy
  ↓
Service
  ↓
Mapper（MyBatis）
  ↓
MySQL / Redis / OSS

响应按相反方向返回
```

- **Tomcat**：Web 服务器 + Servlet 容器，负责接收 HTTP 请求、管理 Servlet。
- **Servlet**：Java Web 处理请求和响应的规范。
- **DispatcherServlet**：Spring MVC 核心 Servlet，负责请求分发。
- **Controller**：接收参数、调用 Service、返回结果。
- **Service**：核心业务逻辑、事务边界。
- **Mapper**：执行 SQL，访问数据库。
- **Filter**：Servlet 层过滤请求。
- **Interceptor**：Spring MVC 层拦截 Controller 请求。
- **AOP**：增强 Spring Bean 方法。

### Maven 多模块

```text
sky-take-out
├── sky-common
├── sky-pojo
└── sky-server
```

- **sky-common**：常量、异常、工具类、公共配置。
- **sky-pojo**：Entity、DTO、VO。
- **sky-server**：Controller、Service、Mapper、配置、启动类。
- **本质**：Maven 多模块单体项目，不是微服务。

---

## 2. Entity、DTO、VO

### Entity

- 对应数据库表。
- 主要用于 Mapper / Service 与数据库之间的数据映射。

```text
MySQL ↔ Entity ↔ Mapper
```

### DTO

**Data Transfer Object**

- 接收前端传入的数据。
- 只保留当前接口需要的字段。

```text
前端 → DTO → Controller
```

### VO

**View Object**

- 封装返回给前端的数据。
- 避免返回密码、内部字段等敏感信息。

```text
Controller → VO → 前端
```

```text
DTO：接收前端参数
Entity：数据库映射
VO：返回前端数据
```

---

## 3. Spring IOC 与 DI

### IOC

**Inversion of Control，控制反转**：对象创建和生命周期交给 Spring 容器管理，而不是业务代码手动 `new`。

### DI

**Dependency Injection，依赖注入**：Spring 自动把 Bean 注入需要它的对象。

```java
@Service
public class OrderServiceImpl {
    private final OrderMapper orderMapper;

    public OrderServiceImpl(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }
}
```

### 常见 Bean 注解

- `@Component`：通用组件。
- `@Service`：业务层。
- `@Repository`：数据访问层。
- `@Controller`：MVC Controller。
- `@RestController`：Controller + `@ResponseBody`。
- `@Configuration`：配置类。
- `@Bean`：通过方法注册 Bean。

### 高频点

- Spring Bean 默认作用域：**singleton**。
- 单例不等于线程安全。
- Controller / Service 不应保存请求级可变成员变量。
- 推荐构造器注入。

---

## 4. Spring Bean 生命周期

```text
实例化
  ↓
属性注入
  ↓
Aware 回调
  ↓
BeanPostProcessor before
  ↓
@PostConstruct
  ↓
初始化方法
  ↓
BeanPostProcessor after
  ↓
Bean 可用
  ↓
@PreDestroy
  ↓
销毁
```

- AOP 代理通常由 `BeanPostProcessor` 创建。
- Bean 生命周期由 Spring 容器管理。

---

## 5. Filter、Interceptor、AOP

| 技术        | 所属       | 拦截对象        | 常见用途                   |
| ----------- | ---------- | --------------- | -------------------------- |
| Filter      | Servlet    | HTTP 请求       | 编码、CORS、请求包装       |
| Interceptor | Spring MVC | Controller 请求 | JWT、登录、权限            |
| AOP         | Spring     | Bean 方法       | 日志、事务、耗时、公共字段 |

### 执行顺序

```text
Filter.before
  ↓
DispatcherServlet
  ↓
Interceptor.preHandle
  ↓
Controller
  ↓
AOP Before / Around
  ↓
Service / Mapper
  ↓
AOP After
  ↓
Controller 返回
  ↓
Interceptor.postHandle
  ↓
Interceptor.afterCompletion
  ↓
Filter.after
```

### Filter

核心：

```java
chain.doFilter(request, response);
```

不调用就不会继续执行请求链。

### Interceptor

核心：

```java
boolean preHandle(...)
```

- `true`：放行。
- `false`：拦截。

生命周期：

```text
preHandle → Controller → postHandle → afterCompletion
```

---

## 6. AOP 核心概念

- **Aspect**：切面，公共逻辑集合。
- **Pointcut**：切入点，决定拦截哪些方法。
- **Advice**：通知，决定何时执行增强逻辑。
- **JoinPoint**：连接点，被增强的方法。
- **Target**：目标对象。
- **Proxy**：Spring 创建的代理对象。

```text
Pointcut：拦谁
Advice：干什么
Aspect：Pointcut + Advice
```

### 常见通知

- `@Before`：方法执行前。
- `@After`：方法结束后，不管是否异常。
- `@AfterReturning`：正常返回后。
- `@AfterThrowing`：异常后。
- `@Around`：方法执行前后都可处理。

---

## 7. `@Around` 环绕通知

```java
@Around("execution(* com.sky.service.impl.*.*(..))")
public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
    long begin = System.currentTimeMillis();
    Object result = joinPoint.proceed();
    long cost = System.currentTimeMillis() - begin;
    System.out.println("耗时：" + cost + "ms");
    return result;
}
```

- `joinPoint.proceed()`：执行原方法。
- 不调用 `proceed()`：目标方法不会执行。
- `return result`：继续返回原方法结果。
- `ProceedingJoinPoint`：`@Around` 使用，可主动调用 `proceed()`。
- `JoinPoint`：可获取方法、参数、目标对象，但不能主动执行目标方法。

---

## 8. 苍穹外卖 AOP：公共字段自动填充

典型字段：

```text
createTime
updateTime
createUser
updateUser
```

实现：

```text
@AutoFill
  ↓
AutoFillAspect
  ↓
@Before
  ↓
获取方法参数
  ↓
反射调用 setter
  ↓
填充时间和操作人
```

```java
@AutoFill(OperationType.INSERT)
void insert(Employee employee);
```

涉及知识：

```text
自定义注解 + AOP + 反射 + ThreadLocal
```

---

## 9. Spring AOP 底层原理

Spring AOP 主要使用**动态代理**。

### JDK 动态代理

- 基于接口。
- 目标类实现接口。

### CGLIB

- 基于继承生成子类代理。
- 不要求目标类实现接口。
- `final` 类 / `final` 方法不能被正常继承增强。

### AOP 失效：同类内部调用

```java
public void methodA() {
    methodB();
}
```

```text
外部调用 → Proxy → Target
内部调用 → this.methodB() → 绕过 Proxy
```

`@Transactional` 同样存在该问题。

---

## 10. JWT 认证

```text
登录成功
  ↓
生成 JWT
  ↓
客户端保存
  ↓
请求头携带 JWT
  ↓
Interceptor 校验
  ↓
解析用户 ID
  ↓
ThreadLocal 保存
  ↓
Controller / Service / AOP 使用
```

### 特点

- 自包含。
- 无状态。
- 适合前后端分离。
- 多实例无需共享 Session。

### 缺点

- 签发后不容易主动失效。
- 可结合短过期时间、刷新 Token、Redis 黑名单。

---

## 11. ThreadLocal

```text
Interceptor
  ↓
BaseContext.setCurrentId(userId)
  ↓
Service / AOP
  ↓
BaseContext.getCurrentId()
```

- 每个线程保存独立变量。
- 避免 userId 层层传参。
- 线程池会复用线程，使用完应 `remove()`。
- 异步线程不能直接依赖父线程 ThreadLocal。

---

## 12. Spring MVC 常见注解

- `@RestController`：REST Controller。
- `@RequestMapping`：公共请求路径。
- `@GetMapping`：GET。
- `@PostMapping`：POST。
- `@PutMapping`：PUT。
- `@DeleteMapping`：DELETE。
- `@RequestBody`：JSON → Java 对象。
- `@PathVariable`：路径参数。
- `@RequestParam`：Query 参数。
- `@Validated` / `@Valid`：参数校验。

---

## 13. 全局异常处理

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BaseException.class)
    public Result handle(BaseException e) {
        return Result.error(e.getMessage());
    }
}
```

- 统一处理异常。
- Controller 不重复写 `try-catch`。
- 统一错误返回格式。

HTTP 状态码：

- `400`：参数错误。
- `401`：未认证 / Token 无效。
- `403`：已认证但无权限。
- `404`：资源不存在。
- `500`：服务器内部异常。

---

## 14. Spring 事务

```java
@Transactional
public void submitOrder() {
}
```

### ACID

- **A 原子性**：全部成功或全部失败。
- **C 一致性**：事务前后数据满足约束。
- **I 隔离性**：事务之间减少互相干扰。
- **D 持久性**：提交后永久保存。

### 默认回滚

默认对 `RuntimeException` 和 `Error` 回滚。

```java
@Transactional(rollbackFor = Exception.class)
```

可覆盖受检异常。

### 常见失效

- 同类内部调用。
- 方法不由 Spring 管理。
- 异常被 `try-catch` 吃掉。
- 异常类型不满足回滚规则。
- 数据库引擎不支持事务。

---

## 15. MyBatis

- Java 方法与 SQL 映射。
- 动态 SQL 适合组合查询和批量操作。

常用动态标签：

```text
<if>
<where>
<set>
<foreach>
```

### `#{}` 与 `${}`

- `#{}`：PreparedStatement 参数绑定，可防 SQL 注入。
- `${}`：字符串直接拼接，有 SQL 注入风险。

---

## 16. PageHelper 分页

```java
PageHelper.startPage(page, pageSize);
Page<Employee> result = employeeMapper.pageQuery(dto);
```

```text
startPage
  ↓
拦截后续查询
  ↓
自动添加 limit
  ↓
执行 count 查询
```

`startPage()` 必须在查询前调用。

---

## 17. MySQL 索引

### 常见索引

- 主键索引。
- 唯一索引。
- 普通索引。
- 联合索引。

### 联合索引

遵循**最左前缀原则**。

```text
(a,b,c)
```

通常可有效支持：

```text
a
a,b
a,b,c
```

### 常见失效

- 对索引列使用函数。
- 隐式类型转换。
- 跳过联合索引最左字段。
- 前置模糊查询 `%xxx`。
- 优化器判断全表扫描成本更低。

### 缺点

- 占空间。
- 写操作需要维护索引。

---

## 18. MySQL 事务隔离级别

```text
READ UNCOMMITTED
READ COMMITTED
REPEATABLE READ
SERIALIZABLE
```

常见问题：

- 脏读。
- 不可重复读。
- 幻读。

MySQL InnoDB 默认：`REPEATABLE READ`。

---

## 19. Redis

用途：

- 热点数据缓存。
- 验证码。
- Token 黑名单。
- 分布式锁。
- 计数器。
- 排行榜。

### Spring Cache

- `@Cacheable`：有缓存直接返回，没有则执行并写缓存。
- `@CacheEvict`：数据修改后删除缓存。

### 常见读写策略

```text
读：Redis → 未命中 → MySQL → 写 Redis
写：更新 MySQL → 删除 Redis
```

---

## 20. Redis 三大缓存问题

### 缓存穿透

查询数据库根本不存在的数据。

解决：

- 缓存空值。
- Bloom Filter。

### 缓存击穿

热点 Key 过期，大量请求同时访问数据库。

解决：

- 互斥锁。
- 逻辑过期。
- 热点 Key 不过期。

### 缓存雪崩

大量 Key 同时过期或 Redis 整体不可用。

解决：

- 过期时间加随机值。
- Redis 高可用。
- 限流 / 降级。

---

## 21. Redis 持久化

### RDB

- 定期快照。
- 恢复快。
- 可能丢失最近一段数据。

### AOF

- 记录写命令。
- 数据更完整。
- 文件通常更大。

---

## 22. WebSocket

```text
HTTP：客户端请求 → 服务端响应
WebSocket：客户端 ↔ 服务端长连接
```

苍穹外卖：

```text
用户下单 → 服务端 → WebSocket → 管理端实时收到新订单提醒
```

- 服务端可主动推送。
- 实时性高。
- 减少轮询。

---

## 23. 定时任务

Spring：

```text
@EnableScheduling
@Scheduled
```

苍穹外卖：

- 超时未支付订单自动取消。
- 派送订单定时完成。

高频点：

- 定时任务要保证幂等。
- 多实例部署可能重复执行。
- 可使用分布式锁或调度平台。

---

## 24. 幂等性

同一个业务请求执行多次，最终结果与执行一次一致。

典型场景：

- 重复提交订单。
- 支付回调重复通知。
- MQ 消息重复消费。
- 定时任务重复执行。

常见实现：

- 唯一索引。
- Token。
- Redis `SETNX`。
- 状态机。
- 业务唯一号。

---

## 25. 乐观锁与悲观锁

### 乐观锁

认为冲突较少。

常见：

- version 字段。
- CAS。
- 条件 UPDATE。

库存示例：

```sql
update dish
set stock = stock - 1
where id = ? and stock > 0;
```

### 悲观锁

认为冲突较多，先加锁再操作。

```sql
select ... for update;
```

---

## 26. 防止超卖

```sql
update product
set stock = stock - 1
where id = ? and stock > 0;
```

判断影响行数：

```text
1 → 成功
0 → 库存不足
```

高并发可进一步使用：

- Redis + Lua。
- 消息队列削峰。
- 乐观锁。
- 分布式锁。

---

## 27. Java 集合高频

### ArrayList

- 动态数组。
- 随机查询快。
- 中间插入删除成本较高。
- 非线程安全。

### LinkedList

- 双向链表。
- 随机访问慢。
- 首尾插入删除方便。

### HashMap

- JDK 8：数组 + 链表 + 红黑树。
- Key 可为 `null`。
- 非线程安全。

### ConcurrentHashMap

- 线程安全。
- JDK 8 主要使用 CAS + synchronized 控制并发。

### HashMap 高频

- 容量通常为 2 的幂，便于 `hash & (length - 1)` 定位。
- `equals()` 相等的对象应保证 `hashCode()` 相同。

---

## 28. Java 线程与线程池

### 线程池优点

- 避免频繁创建销毁线程。
- 控制线程数量。
- 提高资源利用率。

### ThreadPoolExecutor 核心参数

- corePoolSize。
- maximumPoolSize。
- keepAliveTime。
- workQueue。
- threadFactory。
- rejectedExecutionHandler。

### 执行顺序

```text
核心线程未满 → 创建核心线程
核心线程满 → 进入队列
队列满 → 创建非核心线程
达到最大线程数 → 拒绝策略
```

---

## 29. synchronized 与 volatile

### synchronized

- 保证互斥。
- 保证可见性。
- 可修饰方法 / 代码块。

### volatile

- 保证可见性。
- 禁止部分指令重排序。
- 不保证复合操作原子性。

`count++` 不是原子操作。

---

## 30. JVM 内存结构

```text
线程共享：Heap、Method Area / Metaspace
线程私有：Java Stack、Program Counter、Native Method Stack
```

- **Heap**：对象实例，GC 主要区域。
- **Stack**：方法调用栈帧、局部变量、操作数栈等。

### GC

主流 JVM 使用**可达性分析**判断对象是否存活。

常见 GC Roots：

- 栈中的引用。
- 静态变量引用。
- JNI 引用。

---

## 31. String 高频

### `==`

- 基本类型：比较值。
- 引用类型：比较引用地址。

### `equals()`

String 重写了 `equals()`，比较字符串内容。

### String 不可变优点

- 安全。
- 可缓存 hashCode。
- 适合字符串常量池。
- 更容易保证线程安全。

---

## 32. `final`、`finally`、`finalize`

- `final`：修饰变量、方法、类。
- `finally`：异常处理中的最终执行块。
- `finalize`：旧版对象回收回调机制，已不推荐。

---

## 33. Spring Boot

核心作用：

- 自动配置。
- Starter 依赖管理。
- 内嵌 Tomcat。
- 简化 Spring 项目搭建。

### `@SpringBootApplication`

组合：

```text
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan
```

### 自动配置

```text
classpath 依赖
+
配置文件
+
条件注解
→ 自动注册 Bean
```

常见条件注解：

- `@ConditionalOnClass`
- `@ConditionalOnMissingBean`
- `@ConditionalOnProperty`

---

## 34. Spring MVC 参数转换

```java
@PostMapping("/employee")
public Result save(@RequestBody EmployeeDTO dto) {
}
```

```text
HTTP JSON
  ↓
HttpMessageConverter
  ↓
Jackson
  ↓
EmployeeDTO
```

返回对象时反向序列化成 JSON。

---

# 项目提升技术

## 35. Spring Security【高优先级】

用途：

- 登录认证。
- 权限控制。
- 角色管理。
- 方法级鉴权。

```java
@PreAuthorize("hasRole('ADMIN')")
```

### 与 Interceptor 区别

- Interceptor：MVC 通用拦截机制。
- Spring Security：完整安全框架，包含认证、授权、过滤器链、安全上下文。

---

## 36. Kafka【高优先级】

适合：

```text
用户下单
  ↓
订单核心逻辑
  ↓
Kafka
  ├── 消息通知
  ├── 运营统计
  ├── 优惠券处理
  └── 其他非核心业务
```

### 作用

- 异步。
- 解耦。
- 削峰。
- 消息持久化。

### 高频概念

- Producer。
- Broker。
- Consumer。
- Topic。
- Partition。
- Consumer Group。
- Offset。

### 高频问题

- 消息丢失。
- 重复消费。
- 顺序消息。
- 消息积压。

重复消费需要业务幂等：

- 唯一业务 ID。
- Redis 去重。
- 数据库唯一索引。

### 本项目落地：订单创建后异步统计

```text
订单、订单明细、清空购物车（同一事务）
  ↓ 事务提交成功
OrderCreatedEvent → Kafka Topic：order.created
  ↓ 消费组 sky-order-statistics
Redis：order:statistics:created:{日期}
```

- 通过 `@TransactionalEventListener` 在**事务提交后**再发 Kafka，避免数据库回滚但统计已增加。
- 消费端以 `orderId` 写 Redis 去重 Key；Kafka 通常按“至少一次”消费，重复消息不能重复计数。
- Kafka 适合通知、统计等非核心链路：下单接口不等待统计完成，达到解耦和削峰的效果。
- 生产环境还要加 Outbox 本地消息表、发送重试和监控，处理“数据库已提交但 Kafka 发送失败”的最终一致性问题。

---

## 37. RabbitMQ【订单超时取消】

更适合业务消息和延迟消息。

苍穹外卖可将：

```text
Spring Task 扫描超时订单
```

升级为：

```text
下单 → 延迟消息 → 15 分钟后消费 → 未支付则取消
```

减少周期性数据库扫描。

### 本项目落地：TTL + 死信队列

```text
订单事务提交成功
  ↓
order.delay.exchange → order.delay.queue（TTL：15 分钟）
  ↓ 死信转发
order.cancel.exchange → order.cancel.queue → 取消消费者
```

- 不依赖 RabbitMQ 延迟消息插件，使用队列 TTL 与死信交换机实现延迟。
- 消费者执行条件更新：`where id = ? and status = 待支付`。消息重复、订单已支付/取消时影响行数为 0，因此不会误取消。
- RabbitMQ 更适合业务任务、可靠投递和路由；它替代了定时扫描所有超时订单的方式。
- 生产环境应开启 publisher confirm、消费者手动 ACK、失败重试与死信告警；多种延迟时间且要求精确时可使用延迟插件。

---

## 38. Spring AI【项目亮点】

### AI 智能客服

```text
用户问题 → Spring AI → 大模型 → 回答菜品、配送、订单问题
```

### RAG 菜单问答

```text
菜品 / 套餐资料
  ↓
Embedding
  ↓
向量数据库
  ↓
检索相关内容
  ↓
LLM 生成回答
```

### AI 运营分析

输入：

```text
订单量、销售额、热销菜品、退款情况
```

输出：

```text
经营总结、异常分析、运营建议
```

### 核心概念

- Prompt。
- LLM。
- Embedding。
- Vector Database。
- RAG。
- Tool Calling。
- Streaming。

---

## 39. Redis 分布式锁【高优先级】

适合：

- 防重复下单。
- 并发修改同一资源。
- 多实例定时任务防重复执行。

推荐使用 **Redisson**。

需要理解：

- 锁过期。
- 自动续期。
- 锁误删。
- 可重入。

### 本项目落地：防同一用户重复下单

```text
SET order:submit:lock:{userId} randomToken NX EX 30
  ├─ 成功：执行下单事务
  └─ 失败：返回“订单正在提交，请勿重复操作”
事务完成：Lua 比较 token 相等才删除锁
```

- `SET NX EX` 必须原子设置：不能先 `setnx` 再设置过期时间，否则异常会形成死锁。
- 锁值使用 UUID token，释放时 Lua 原子“比较并删除”，避免锁过期后误删其他请求的新锁。
- 当前实现让锁持有到事务完成；锁超时时间要大于正常业务耗时。超长业务可使用 Redisson 的看门狗续期。
- 锁不能取代数据库约束；仍应通过唯一索引、状态机或条件更新兜底。

---

## 40. Elasticsearch【中优先级】

适合：

- 菜品搜索。
- 商家搜索。
- 关键词全文检索。

核心概念：

- Index。
- Document。
- Mapping。
- Inverted Index。
- Analyzer。

---

## 41. Docker【高优先级】

容器化：

```text
Spring Boot
MySQL
Redis
Kafka
```

核心：

```text
Dockerfile
docker-compose.yml
```

作用：

- 环境一致。
- 快速部署。
- 方便项目演示。

---

## 42. Nginx【高优先级】

用途：

- 反向代理。
- 静态资源部署。
- HTTPS。
- 负载均衡。

```text
客户端 → Nginx → Spring Boot
```

多实例：

```text
             → Spring Boot 8081
Nginx  →
             → Spring Boot 8082
```



---

## 46. 单元测试【中优先级】

常用：

```text
JUnit
Mockito
@SpringBootTest
```

测试：

- Service。
- Mapper。
- Controller。
- Redis。
- 关键业务边界。

---

# 高频面试速答

## 47. IOC 和 DI 区别

- IOC：对象管理权交给 Spring。
- DI：Spring 把依赖对象注入 Bean。

## 48. `@Component` 和 `@Bean`

- `@Component`：标在类上，通过组件扫描注册。
- `@Bean`：标在方法上，适合第三方类或自定义创建过程。

## 49. `@Controller` 和 `@RestController`

```text
@RestController = @Controller + @ResponseBody
```

## 50. Filter 和 Interceptor

```text
Filter：Servlet 层
Interceptor：Spring MVC 层
```

Filter 更靠前，Interceptor 更容易获取 Controller 信息。

## 51. Interceptor 和 AOP

```text
Interceptor：HTTP / Controller 请求
AOP：Spring Bean 方法
```

## 52. AOP 为什么能无侵入增强

```text
调用者 → Proxy → 增强逻辑 → Target
```

业务类本身无需重复写公共逻辑。

## 53. `@Transactional` 为什么会失效

核心原因之一：**没有经过 Spring 代理**，典型是同类内部调用。

## 54. Redis 为什么快

- 数据主要在内存。
- 高效数据结构。
- 命令执行模型简单。
- 减少磁盘随机 IO。

## 55. Redis 和 MySQL 如何保证一致性

常用：

```text
更新数据库 → 删除缓存
```

高一致性场景可结合 MQ、CDC 等方案。

## 56. 为什么 JWT 不用 Session

JWT：无状态、多实例部署简单、适合前后端分离。
Session：服务端保存状态，多实例需要共享 Session 或粘性会话。

## 57. 为什么使用 DTO / VO

- DTO 控制输入字段。
- VO 控制输出字段。
- Entity 不直接暴露给外部。
- 降低接口与数据库结构耦合。

## 58. 为什么 Service 层需要事务

Service 通常代表完整业务操作，例如：

```text
创建订单 + 写订单明细 + 扣库存
```

任一步失败都应整体回滚。

## 59. MyBatis `#{}` 为什么防 SQL 注入

使用 PreparedStatement 参数绑定，使 SQL 结构与参数值分离。

## 60. 什么是幂等

同一个业务请求重复执行多次，最终结果与执行一次一致。

## 61. Kafka 为什么能削峰

```text
大量请求 → Kafka → 消费者按处理能力持续消费
```

避免瞬时流量直接冲击数据库和下游系统。

---

# 项目升级优先级

```text
第一梯队：
Kafka / RabbitMQ
Redis 分布式锁
Spring Security
Docker
Nginx

第二梯队：
Spring AI + RAG
Elasticsearch
接口限流
Actuator + Prometheus + Grafana

第三梯队：
Spring Cloud 微服务
分布式事务
链路追踪
Kubernetes
```

## 推荐升级组合

```text
Spring Boot
+ MyBatis
+ MySQL
+ Redis
+ JWT / Spring Security
+ Kafka
+ Redisson
+ WebSocket
+ Spring AI
+ Elasticsearch
+ Docker
+ Nginx
```

## 可形成的项目亮点

```text
1. Redis 缓存热点菜品，数据库更新后删除缓存。
2. Redisson 分布式锁防重复下单和多实例任务重复执行。
3. Kafka 将订单通知、运营统计等非核心逻辑异步化。
4. Spring Security + JWT 实现认证和角色权限控制。
5. Elasticsearch 实现菜品全文检索。
6. Spring AI + RAG 实现菜单问答 / 智能客服。
7. WebSocket 实现新订单实时推送。
8. Docker + Nginx 实现容器化部署和反向代理。
9. Actuator + Prometheus + Grafana 实现服务监控。
```
