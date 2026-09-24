# Java 面试笔记（精简版）

> 答题思路：先给结论，再讲原理，最后联系项目场景。示例以苍穹外卖为背景。

## 一、Spring 面试题

### 1. IOC 和 DI 是什么？

- **IOC（控制反转）**：对象的创建和管理交给 Spring，而不是业务代码自己 `new`。
- **DI（依赖注入）**：Spring 将 Bean 注入另一个 Bean，例如把 `OrderMapper` 注入 `OrderServiceImpl`。

```java
@Service
public class OrderServiceImpl {
    private final OrderMapper orderMapper;

    public OrderServiceImpl(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }
}
```

推荐构造器注入：依赖明确、方便单测，也能避免部分循环依赖。

### 2. 常用 Bean 注解与作用域

| 注解 | 作用 |
| --- | --- |
| `@Component` | 通用组件 |
| `@Service` | 业务层 |
| `@Repository` | 数据访问层 |
| `@RestController` | 返回 JSON 的 Controller |
| `@Configuration` + `@Bean` | 配置类或第三方对象注册 |

Bean 默认是 **singleton**。单例不等于线程安全，Controller/Service 不应保存当前用户、订单号等请求级可变成员变量。

### 3. Spring Bean 生命周期？

```text
实例化 → 属性注入 → Aware 回调 → BeanPostProcessor 前置
→ 初始化（@PostConstruct / init-method）→ BeanPostProcessor 后置 → 可使用
→ 容器关闭（@PreDestroy）
```

`BeanPostProcessor` 可以在 Bean 初始化前后增强对象，AOP 代理等能力与它有关。

### 4. `@Component` 和 `@Bean` 的区别？

- `@Component` 标在类上，通过组件扫描自动注册，适合自己编写的类。
- `@Bean` 标在方法上，适合第三方类或需要自定义构造过程的对象。

项目例子：`RedisConfiguration` 用 `@Bean` 注册定制过序列化器的 `RedisTemplate`。

### 5. Filter、Interceptor、AOP 如何区分？

```text
HTTP 请求 → Filter → DispatcherServlet → Interceptor → Controller
                                              ↓
                                      Service / Mapper（可被 AOP 增强）
```

| 机制 | 适合场景 |
| --- | --- |
| Filter | 编码、跨域、请求包装等 Servlet 级通用处理 |
| Interceptor | 登录认证、权限校验等 MVC 请求处理 |
| AOP | 日志、事务、公共字段填充等 Bean 方法横切逻辑 |

项目中 JWT 放在 Interceptor；`@AutoFill` 公共字段填充放在 AOP。

### 6. AOP 核心概念与动态代理

- **切面 Aspect**：横切逻辑集合，如 `AutoFillAspect`。
- **切入点 Pointcut**：拦截哪些方法。
- **通知 Advice**：拦截后做什么，常用 `@Before`、`@Around`。
- **代理 Proxy**：Spring 生成的增强对象。

有接口通常使用 JDK 动态代理，无接口通常使用 CGLIB。环绕通知必须执行 `proceed()`，否则目标方法不会运行。

```java
@Around("execution(* com.sky.service..*(..))")
public Object around(ProceedingJoinPoint pjp) throws Throwable {
    long start = System.currentTimeMillis();
    Object result = pjp.proceed();
    log.info("耗时={}ms", System.currentTimeMillis() - start);
    return result;
}
```

### 7. `@Transactional` 原理与失效场景？

Spring 通过 AOP 代理，在方法前开启事务，正常结束提交，异常时回滚。

- 默认对 `RuntimeException`、`Error` 回滚；受检异常需指定 `rollbackFor = Exception.class`。
- 同类内部 `this.xxx()` 调用没有经过代理，事务可能失效。
- 异常被吞掉、方法不是 `public`、Bean 不归 Spring 管理，也会导致失效。

项目例子：新增订单需同时写订单、订单明细并清空购物车，任一步失败都应整体回滚。

### 8. 事务的 ACID 与隔离级别？

- **原子性**：要么都成功，要么都回滚。
- **一致性**：事务前后数据满足约束。
- **隔离性**：并发事务互不干扰。
- **持久性**：提交后数据不会丢失。

隔离级别从低到高：读未提交、读已提交、可重复读、串行化。MySQL InnoDB 默认可重复读。

### 9. Spring MVC 常用注解？

- `@RequestBody`：接收 JSON 请求体。
- `@PathVariable`：接收路径参数，如 `/order/{id}`。
- `@RequestParam`：接收查询参数。
- `@RestControllerAdvice` + `@ExceptionHandler`：统一异常响应。

项目中 DTO 接收请求、Entity 映射数据库、VO 返回前端，避免直接暴露密码等字段。

### 10. JWT + ThreadLocal 的登录流程？

```text
登录成功 → 签发 JWT → 请求头携带 Token → Interceptor 校验
→ BaseContext(ThreadLocal) 保存用户 ID → Service/AOP 使用 ID
```

- JWT 无状态，适合前后端分离和多实例部署；缺点是主动失效较复杂。
- `ThreadLocal` 是线程副本，不是全局变量；线程池复用后必须 `remove()`，防止用户数据串线。
- `401` 是未认证，`403` 是已认证但无权限。

### 11. MyBatis 与 PageHelper 要点？

- `#{}` 使用 PreparedStatement 参数绑定，可防 SQL 注入；`${}` 是字符串拼接，应谨慎使用。
- PageHelper 必须在查询前调用：

```java
PageHelper.startPage(page, pageSize);
Page<Orders> result = orderMapper.pageQuery(dto);
```

### 12. Redis 缓存与一致性？

常用 Cache Aside：**读缓存，未命中查数据库后回填；写数据库后删除缓存**。

- 缓存穿透：缓存空值或布隆过滤器。
- 缓存击穿：互斥锁、逻辑过期。
- 缓存雪崩：随机过期时间、限流、Redis 高可用。

项目中菜品/套餐列表使用缓存，修改后清理相关 Key。

### 13. Redis 分布式锁如何安全实现？

```text
SET lockKey uuid NX EX 30
成功 → 执行业务；失败 → 返回“处理中”
释放 → Lua：value 相同才 DEL
```

不能“先 `setnx` 后 `expire`”，否则异常可能产生死锁；不能直接删除锁，否则可能误删锁过期后其他线程新获得的锁。

项目中按用户 ID 锁定下单请求，防止双击重复创建订单。数据库唯一约束、条件更新仍是最终兜底。

### 14. Kafka 与 RabbitMQ 在项目中如何使用？

| 组件 | 项目场景 | 关键点 |
| --- | --- | --- |
| Kafka | 订单创建后的异步统计 | `order.created` 事件；消费者按订单 ID 去重 |
| RabbitMQ | 15 分钟未支付订单取消 | TTL + 死信队列；按“ID + 待支付状态”条件更新 |

订单事件在 `@TransactionalEventListener` 中、事务提交后投递，避免“数据库回滚但消息已发送”。生产环境还应使用 Outbox、重试、确认机制处理最终一致性。

### 15. WebSocket 与定时任务？

- WebSocket 是双向长连接，项目用于向管理端实时推送新订单提醒。
- `@EnableScheduling` + `@Scheduled` 可执行定时任务。
- 多实例下定时任务可能重复执行，需要分布式锁或调度平台；任务和消息消费者都应设计为幂等。

---

## 二、Java 基础面试题

### 1. `==` 和 `equals()` 的区别？

- 基本类型的 `==` 比较值；引用类型的 `==` 比较是否同一对象。
- `equals()` 默认等同于 `==`，许多类（如 `String`）重写后比较内容。

```java
new String("a").equals("a"); // true：内容相同
new String("a") == "a";      // false：不是同一对象
```

### 2. String 为什么不可变？StringBuilder 和 StringBuffer 怎么选？

String 不可变，便于字符串常量池复用、缓存 hash 值和保证线程安全，也更适合做 HashMap Key。

- 少量拼接：`String`。
- 单线程大量拼接：`StringBuilder`（快）。
- 多线程共享拼接：`StringBuffer`（方法同步，较慢）。

### 3. `final`、`finally`、`finalize`？

- `final`：变量不可重新赋值、方法不可重写、类不可继承。
- `finally`：异常处理后的必执行代码块（JVM 退出等极端情况除外）。
- `finalize`：已废弃，不应依赖它释放资源。

### 4. `try-catch-finally` 中 return 的注意点？

`finally` 中不要写 `return`：它会覆盖 `try/catch` 的返回值或异常。资源应使用 try-with-resources 自动关闭。

```java
try (InputStream in = ...) {
    // 使用资源
}
```

### 5. 异常体系如何划分？

`Throwable` 分为 `Error` 与 `Exception`；Exception 又分受检异常和运行时异常。

- 受检异常：必须捕获或声明抛出，如 `IOException`。
- 运行时异常：通常表示编程错误，如 `NullPointerException`。
- 业务异常：项目中继承 `BaseException`，交给全局异常处理器统一返回。

### 6. 接口和抽象类的区别？

- 抽象类用于“is-a”共性实现，可有成员变量、构造器和普通方法，单继承。
- 接口用于能力约束，可多实现；Java 8 起可有 default/static 方法。

优先面向接口编程：Service 定义接口，Impl 负责实现，便于替换和测试。

### 7. 重载与重写？

- **重载**：同一类方法名相同、参数列表不同，编译期决定。
- **重写**：子类改写父类方法，方法签名相同，运行期动态绑定。

重写不能缩小访问权限，不能抛出更宽泛的受检异常。

### 8. Java 是值传递还是引用传递？

Java **只有值传递**。对象变量传递的是“对象引用的副本”，所以可通过副本修改对象内容，但不能让调用方的引用改指向新对象。

### 9. 深拷贝与浅拷贝？

- 浅拷贝：复制对象本身，内部引用仍共享。
- 深拷贝：内部引用对象也复制。

DTO/VO 转换时常用 `BeanUtils.copyProperties`，属于字段复制；嵌套集合或对象仍需按业务显式处理。

### 10. `Optional` 应如何使用？

适合表达“可能不存在”的返回值，避免层层空判断；不建议作为实体字段或方法入参。

```java
String name = Optional.ofNullable(user)
        .map(User::getName).orElse("游客");
```

---

## 三、Java 集合面试题

### 1. List、Set、Map 怎么选？

| 接口 | 特点 | 常见实现 |
| --- | --- | --- |
| List | 有序、可重复 | ArrayList、LinkedList |
| Set | 不可重复 | HashSet、TreeSet |
| Map | Key-Value，Key 不重复 | HashMap、ConcurrentHashMap |

### 2. ArrayList 和 LinkedList？

- ArrayList 底层动态数组：按下标查询快，尾部追加快，中间插入/删除需要搬移元素。
- LinkedList 底层双向链表：已定位节点后插删快，但按下标查询需遍历，且节点额外占内存。

大多数业务场景选择 ArrayList；不要因“链表插入快”就盲目选 LinkedList。

### 3. HashMap 原理？

JDK 8 的 HashMap 是 **数组 + 链表/红黑树**。

```text
hash(key) → 定位数组桶 → equals 比较 Key → 覆盖或新增
```

- 链表较长且数组容量达到条件后会树化，降低极端冲突查询成本。
- Key 必须正确重写 `hashCode()` 和 `equals()`；两对象 `equals` 为 true，hashCode 必须相同。
- HashMap 线程不安全；并发使用 `ConcurrentHashMap`。

### 4. HashMap 为什么容量常为 2 的幂？

通过 `(n - 1) & hash` 快速定位桶。容量为 2 的幂可让低位 hash 更均匀参与计算，减少冲突。

### 5. HashSet 为什么能去重？

HashSet 底层由 HashMap 实现，元素作为 Map 的 Key。先比较 hashCode 定位桶，再用 equals 判断是否重复。

### 6. ConcurrentHashMap 如何保证并发？

JDK 8 主要使用 CAS + `synchronized` 锁住单个桶，而非整张表；读操作大多无需加锁，并发度高于 Hashtable。

它不允许 null Key/Value，因为并发环境下无法区分“Key 不存在”还是“Value 为 null”。

### 7. fail-fast 与 fail-safe？

- fail-fast：遍历时集合结构被修改，快速抛 `ConcurrentModificationException`，如 ArrayList 迭代器。
- fail-safe：遍历副本或弱一致性数据，不一定抛异常，如 CopyOnWriteArrayList、ConcurrentHashMap。

遍历时删除元素应使用 `Iterator.remove()`，或使用并发集合。

### 8. Collections 与 Stream 的使用建议？

Stream 适合转换、过滤、聚合；避免在 Stream 内做复杂 IO、修改外部共享变量或嵌套过深。

```java
List<String> names = orders.stream()
        .filter(o -> o.getStatus().equals(Orders.COMPLETED))
        .map(Orders::getNumber)
        .collect(Collectors.toList());
```

---

## 四、Java 并发编程面试题

### 1. 进程、线程、协程？

- 进程是资源分配单位，进程间内存隔离。
- 线程是 CPU 调度单位，同一进程线程共享堆内存。
- 协程是用户态更轻量的执行单元，Java 21 虚拟线程可用于高并发阻塞任务。

### 2. 并发三大问题？

- **原子性**：操作不可被分割。
- **可见性**：一个线程修改对其他线程立即可见。
- **有序性**：指令重排可能改变观察顺序。

### 3. `synchronized` 和 `volatile` 的区别？

| 关键字 | 能解决的问题 |
| --- | --- |
| `synchronized` | 原子性、可见性、有序性；可重入互斥锁 |
| `volatile` | 可见性、有序性；不保证复合操作原子性 |

```java
volatile boolean running = true; // 适合开关
count++; // 不是原子操作，不能仅靠 volatile
```

### 4. `synchronized` 锁在哪里？

- 普通同步方法：当前实例对象。
- 静态同步方法：Class 对象。
- 同步代码块：`synchronized(lock)` 指定对象。

锁应尽量细粒度，不要把网络请求、慢 SQL 放在大锁中。

### 5. CAS 是什么？有什么问题？

CAS（比较并交换）是“期望值相同才更新”的无锁操作，AtomicInteger 等原子类基于它实现。

问题：ABA、长时间自旋开销、一次只能保证一个变量原子更新。复杂状态可使用锁或 AtomicReference。

### 6. `wait()`、`notify()`、`sleep()` 区别？

- `wait/notify` 必须在 synchronized 内使用，`wait` 会释放锁。
- `sleep` 是 Thread 方法，不释放锁，只让当前线程暂停。
- 更推荐使用 `CountDownLatch`、`Semaphore`、`BlockingQueue` 等并发工具类。

### 7. 线程池为什么重要？

复用线程、控制并发数、降低频繁创建线程开销，并提供队列、拒绝策略和监控点。

```text
核心线程 → 工作队列 → 最大线程 → 拒绝策略
```

核心参数：`corePoolSize`、`maximumPoolSize`、`keepAliveTime`、`workQueue`、`threadFactory`、`handler`。

不建议直接用 Executors：其默认队列/最大线程数可能导致内存溢出或线程暴涨。应显式构造 `ThreadPoolExecutor`。

### 8. 常见拒绝策略？

- AbortPolicy：抛异常，默认策略。
- CallerRunsPolicy：调用线程执行任务，形成反压。
- DiscardPolicy：静默丢弃。
- DiscardOldestPolicy：丢弃队列最旧任务。

订单、支付等关键任务不能静默丢弃；应记录日志、重试或转入可靠消息队列。

### 9. `ThreadLocal` 原理与内存泄漏？

每个 Thread 有一个 ThreadLocalMap，Key 是弱引用、Value 是强引用。在线程池中，Key 被回收后 Value 仍可能随线程长期存在。

```java
try {
    BaseContext.setCurrentId(userId);
    // 当前请求业务
} finally {
    BaseContext.removeCurrentId();
}
```

项目 JWT 拦截器存入当前用户 ID 后，应在请求完成时清理。

### 10. 死锁产生条件与排查？

条件：互斥、占有且等待、不可抢占、循环等待。避免方式：统一加锁顺序、缩小锁范围、使用 `tryLock` 超时。

排查：`jstack <pid>` 查看线程栈和 `Found one Java-level deadlock`。

### 11. 幂等与并发控制怎么做？

| 场景 | 推荐方案 |
| --- | --- |
| 用户重复下单 | Redis 分布式锁、业务 Token |
| 库存扣减 | 条件更新/乐观锁 |
| 支付回调、MQ 重复消费 | 唯一业务号、状态机、去重表/缓存 |
| 多实例定时任务 | 分布式锁 |

---

## 五、Java 虚拟机面试题

### 1. JVM 运行时内存区域？

| 区域 | 线程共享 | 存放内容 |
| --- | --- | --- |
| 堆 Heap | 是 | 对象实例、数组，GC 主要区域 |
| 虚拟机栈 | 否 | 栈帧、局部变量、方法调用 |
| 程序计数器 | 否 | 当前线程执行位置 |
| 本地方法栈 | 否 | Native 方法调用 |
| 元空间 Metaspace | 是 | 类元数据、常量池 |

`StackOverflowError` 常由过深递归导致；`OutOfMemoryError` 需结合错误类型判断是堆、元空间还是直接内存不足。

### 2. 对象创建过程？

```text
类加载检查 → 分配内存 → 默认零值 → 设置对象头 → 执行构造方法
```

对象通常在堆上分配；JIT 逃逸分析后，短生命周期对象可能被栈上分配或标量替换。

### 3. 类加载过程与双亲委派？

```text
加载 → 验证 → 准备 → 解析 → 初始化
```

双亲委派：类加载器先委托父加载器加载，父无法加载再自己加载。好处是避免同一类重复加载，并保护 `java.lang.String` 等核心类。

### 4. 哪些对象能作为 GC Root？

- 虚拟机栈中引用的对象。
- 静态变量引用的对象。
- 常量引用的对象。
- JNI 引用的对象。
- 正在运行线程、同步锁持有的对象。

GC 通过可达性分析判断对象是否可回收，不再使用引用计数法（无法解决循环引用）。

### 5. 常见垃圾收集器？

- Serial：单线程，适合小内存客户端。
- Parallel：吞吐量优先。
- CMS：低停顿旧方案，已逐步淘汰。
- G1：面向服务端大堆内存，可预测停顿，JDK 9+ 默认。

面试不必死记参数：重点是吞吐量与停顿时间的权衡。

### 6. Minor GC、Major GC、Full GC？

- Minor/Young GC：回收新生代，频繁但通常快。
- Major/Old GC：主要回收老年代，术语在不同 JVM 中不完全统一。
- Full GC：回收整个堆及可能的元空间，停顿通常最长，应重点关注。

### 7. JVM 调优基本步骤？

```text
监控告警 → 定位（日志/jstat/jmap/jstack）→ 导出堆转储
→ 分析大对象与引用链 → 修复代码或调整参数 → 压测验证
```

常用参数：`-Xms`、`-Xmx`、`-Xlog:gc*`（JDK 9+）。不要没有监控就盲目调大堆内存。

### 8. 常见内存泄漏场景？

- 静态集合持续添加对象。
- 缓存没有过期/淘汰策略。
- ThreadLocal 未清理。
- 连接、流、线程池未关闭。
- 监听器、回调持有对象引用。

项目中 Redis 缓存需设置合理 TTL；线程上下文在请求结束时清理。

## 六、一分钟项目总结

这是一个 Spring Boot 外卖系统后端，使用 MyBatis 与 MySQL 管理核心业务数据，Redis 缓存热点数据。用户通过 JWT 认证，拦截器解析身份并用 ThreadLocal 传递当前用户；AOP 统一填充审计字段。订单、订单明细和购物车操作通过事务保证一致性，PageHelper 负责分页。为处理并发下单，按用户使用 Redis 分布式锁；订单提交后通过 Kafka 异步统计，并通过 RabbitMQ 延迟消息取消超时未支付订单。WebSocket 用于向管理端实时推送新订单提醒。
