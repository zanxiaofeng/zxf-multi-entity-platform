# Spring Boot + Flowable 项目通用公共组件调研

> 目的：梳理在 Spring Boot（3.x/4.x）+ Flowable 项目中，可提取为公共组件/公共 starter 的通用设计，解决日志、审计、下游集成、错误处理、弹性等横切问题。
> 方法：网络调研，以 Flowable 官方文档、Spring 官方文档、microservices.io、Resilience4j/ShedLock 官方仓库为主源（正文标注来源）。
> 与多实体架构的关系：这些组件是 platform-core 的天然候选内容（差异隔离原则下，它们属于"无实体差异的内核能力"）。

---

## 一、组件总览

| # | 组件 | 解决的通用问题 | 归类 |
| --- | --- | --- | --- |
| 1 | 全局事件监听器（审计/日志/指标数据源） | 流程生命周期统一审计、监控埋点 | Flowable |
| 2 | 事件桥接（Flowable 事件 → Spring Event） | 引擎事件与业务模块解耦 | Flowable |
| 3 | JavaDelegate / Listener 通用基类 | 执行日志、耗时、异常分类、MDC | Flowable |
| 4 | AsyncExecutor 调优与死信 Job 运维组件 | 异步作业弹性、死信复活/告警 | Flowable |
| 5 | 错误分类体系（BpmnError vs 技术异常） | 业务错误走分支、技术错误重试 | Flowable |
| 6 | 候选人策略 / ActivityBehaviorFactory | 全局统一审批人分配规则 | Flowable |
| 7 | 自定义 IdGenerator / tenantId 上下文 | ID 可读性、多租户隔离 | Flowable |
| 8 | JSON 流程变量 / 自定义 EL 函数 | 变量可读可查、表达式复用 | Flowable |
| 9 | trace/MDC 全链路透传组件 | 同步/异步/线程池间 traceId 断链 | 可观测性 |
| 10 | 统一错误响应体系（ProblemDetail） | 下游统一解析错误、不泄露堆栈 | 集成 |
| 11 | Resilience4j 容错封装 + 请求拦截器 | 超时/重试/熔断统一策略、correlationId 透传 | 集成 |
| 12 | Transactional Outbox 组件 | 流程事务与发 MQ 的双写一致性 | 集成 |
| 13 | ShedLock / 幂等防重组件 | 多实例调度防重、消费端幂等 | 基础能力 |
| 14 | 企业内部 starter 骨架 | 上述组件"引入即用"的分发机制 | 工程化 |

---

## 二、Flowable 引擎侧公共组件

### 1. 全局事件监听器（GlobalEventListener）

**解决问题**：跨所有流程定义的统一审计日志、流程/任务生命周期监控（`PROCESS_STARTED`、`TASK_CREATED`、`TASK_COMPLETED`、`JOB_EXECUTION_FAILURE` 等），避免在每个 BPMN 里重复声明监听器。

**实现要点**：
- 实现 `org.flowable.common.engine.api.delegate.event.FlowableEventListener`，或继承官方基类 `BaseEntityEventListener`（提供 onCreate/onUpdate/onDelete 钩子）；
- Spring Boot 下通过 `EngineConfigurationConfigurer<SpringProcessEngineConfiguration>` 注册：`setEventListeners(...)`（全量）或 `setTypedEventListeners(Map)`（按类型订阅，性能更好）；运行时 `runtimeService.addEventListener(...)` 重启即失效，仅用于临时调试；
- **关键纪律：`isFailOnException()` 必须返回 false**——审计/监控失败不得回滚业务事务。

来源：[Flowable 官方 ch03 Configuration — Event handlers](https://www.flowable.com/open-source/docs/bpmn/ch03-Configuration)

### 2. 事件桥接组件（Flowable 事件 → Spring ApplicationEvent）

**解决问题**：引擎事件与业务模块解耦，业务方用熟悉的 `@EventListener` / `@TransactionalEventListener` 订阅，不必依赖 Flowable API。

**实现要点**：在 GlobalEventListener 内注入 `ApplicationEventPublisher`，把 FlowableEvent 包装为 Spring 领域事件转发（开源参考：yudao 的 `BpmProcessInstanceEventPublisher`）。配合 `@TransactionalEventListener(AFTER_COMMIT)` 保证只在流程事务提交后触发下游逻辑。

### 3. 通用 JavaDelegate / Listener 基类

**解决问题**：服务任务执行的统一入口日志、耗时统计、异常包装、上下文传递。

**实现要点**：
- 模板方法模式：抽象基类实现 `JavaDelegate.execute()`，内部统一做日志/计时/异常分类，子类只实现 `doExecute(DelegateExecution)`；
- **线程安全纪律**：delegate 是单例（delegateExpression 引用 Spring Bean），禁止实例字段存执行态；
- MDC：Flowable 引擎原生支持 SLF4J MDC，自动注入 `mdcProcessDefinitionID`、`mdcProcessInstanceID`、`mdcExecutionId`、`mdcBusinessKey`（官方已核实），基类只需补充业务键；
- TaskListener / ExecutionListener 同法处理（create/assignment/complete、start/end/take），BPMN 中统一用 `delegateExpression="${beanName}"` 引用以获得依赖注入。

来源：[Flowable 官方 ch03 — Mapped Diagnostic Contexts](https://www.flowable.com/open-source/docs/bpmn/ch03-Configuration#mapped-diagnostic-contexts)

### 4. AsyncExecutor 调优与死信 Job 运维组件

**解决问题**：异步任务/定时器的执行弹性；失败作业的重试与死信处理。

**实现要点**：
- 引擎参数：`asyncExecutorActivate=true`、`asyncExecutorNumberOfRetries`（默认 3）、`asyncFailedJobWaitTime`（重试间隔）；
- 节点级独立重试：BPMN `extensionElements` 中 `<flowable:failedJobRetryTimeCycle>R3/PT5S</flowable:failedJobRetryTimeCycle>`（需 `flowable:async="true"`）；异步边界保证失败只重试出错任务，不回滚已提交的前序任务；
- 重试耗尽后 Job 进入 `ACT_RU_DEADLETTER_JOB` 死信表。死信运维组件应提供：`ManagementService.createDeadLetterJobQuery()` 扫描 + 告警，`moveDeadLetterJobToExecutableJob(jobId, retries)` 复活。官方建议按异常类型分流：网络/IO 类自动复活，代码 bug 类修复后复活，非关键可删除。死信 Job 不含 businessKey，需经 processInstanceId 关联。

来源：[Flowable 官方 ch18 — Async Executor](https://www.flowable.com/open-source/docs/bpmn/ch18-Advanced)、[官方博客：Demystifying the async flag II](https://www.flowable.com/blog/engineering/demystifying-the-asynchronous-flag-ii)

### 5. 错误分类体系（BpmnError vs 技术异常）

**解决问题**：区分"业务错误"（应走流程分支）与"技术错误"（应重试/进死信），这是流程项目最常见的混乱源。

**实现要点**：
- 业务错误：抛 `BpmnError(errorCode)`，由边界错误事件捕获走流程分支，**不触发 Job 重试**；
- 技术异常：直接抛 RuntimeException，配合 `failedJobRetryTimeCycle` 重试，耗尽进死信；
- 官方 HTTP Task 的 `failStatusCodes`（重试）/ `handleStatusCodes`（转 BpmnError）即此模式的样板；
- 错误日志落库注意事务：引擎回滚会带走同事务的业务库写入，错误日志需 `REQUIRES_NEW` 独立事务。

来源：[Flowable 官方 ch07b — HTTP Task error handling](https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs)、[PALO IT：Overcoming common hurdles in Flowable](https://www.palo-it.com/en/blog/overcoming-common-hurdles-in-flowable)

### 6. 候选人策略 / ActivityBehaviorFactory

**解决问题**：全局统一的审批人分配规则（角色/部门/发起人上级等多策略），避免每个流程硬编码。

**实现要点**：`configuration.setActivityBehaviorFactory(...)`（继承 `DefaultActivityBehaviorFactory`）+ 策略接口（如 `TaskCandidateStrategy`）按规则路由候选人/组。开源成熟参考：yudao 的 `BpmActivityBehaviorFactory`。

### 7. 自定义 IdGenerator 与 tenantId 上下文组件

**实现要点**：
- IdGenerator：实现 `org.flowable.common.engine.impl.cfg.IdGenerator#getNextId()`（内置 `StrongUuidGenerator`、`DbIdGenerator`），经 `EngineConfigurationConfigurer` 注册。注意：一个引擎只有一个 IdGenerator 且对所有实体生效；
- 多租户：部署 `createDeployment().tenantId(...)`、启动 `createProcessInstanceBuilder().tenantId(...)`，所有 Query 支持 tenantId 过滤。公共组件封装"租户上下文 → 自动携带 tenantId"。**注意与本文档项目的关系：多实体架构走"一实体一套引擎/库"（各自独立 ACT_* 表），tenantId 机制作为备选对照了解即可**。

来源：[IdGenerator Javadoc](https://developer-docs.flowable.com/javadocs/flowable-oss-javadoc/2025.1/org/flowable/common/engine/impl/cfg/IdGenerator.html)

### 8. JSON 流程变量与自定义 EL 函数

**实现要点**：
- 流程变量用内置 `JsonType`（Jackson JsonNode，支持 trackObjects 变更追踪）替代 Java 序列化 BLOB：可读、可查、无 serialVersionUID 问题；自定义类型实现 `VariableType` 经 `customPreVariableTypes` 注册。**纪律：领域值对象不直接进流程变量**；
- 自定义 EL 函数：实现 `FlowableFunctionDelegate`（`functionPrefix()`/`localName()`），流程表达式里写 `${bpm:hasRole(userId, role)}` 替代冗长表达式，经 `setCustomFlowableFunctionDelegates(...)` 注册。

来源：[JsonType Javadoc](https://developer-docs.flowable.com/javadocs/flowable-oss-javadoc/2025.1/org/flowable/variable/service/impl/types/JsonType.html)

---

## 三、日志与可观测性公共组件

### 9. trace/MDC 全链路透传组件

**解决问题**：HTTP 入口 → 流程执行 → async executor 线程池 → 下游调用，traceId 断链是排障最大痛点。

**实现要点**：
- 同步链路：Micrometer Tracing + OpenTelemetry（`micrometer-tracing-bridge-otel`）+ `OncePerRequestFilter` 兜底生成/透传 traceId；
- **异步断点**：Flowable async executor 是独立线程池，需 `TaskDecorator` / `ContextSnapshot` 显式传播 MDC/Observation 上下文（与多实体文档 5.2.3 的 EntityContext 传播同一机制，可合并为一个装饰器组件）；
- 流程维度指标：在组件 1 的监听器里对接 Micrometer——`Timer` 记流程/节点耗时（tag=processDefinitionKey），`Gauge` 暴露任务积压数与死信 Job 数；
- Flowable 开源版无内置 OTel 支持，需基于事件监听器自建（标注：社区实践，非官方机制）；
- 引擎级审计日志：`enableDatabaseEventLogging`（ACT_EVT_LOG）+ 可插拔 `EventFlusher`（可改写 Kafka/ES）；`LoggingListener` 输出含 scopeId（官方称 correlation ID）的结构化 JSON。

来源：[Flowable 官方 ch18 — Event logging / Logging Sessions](https://www.flowable.com/open-source/docs/bpmn/ch18-Advanced)

---

## 四、下游集成公共组件

### 10. 统一错误响应体系（ProblemDetail / RFC 9457）

**解决问题**：各服务错误格式不一致，下游无法统一解析；生产环境泄露堆栈。

**实现要点**：Spring Boot 3+ 内置 `ProblemDetail`（`application/problem+json`）；`@RestControllerAdvice extends ResponseEntityExceptionHandler`；业务异常继承 `ErrorResponseException`；扩展属性携带 `correlationId`（取 MDC traceId）与 `fieldErrors`。两个实测坑：`@ExceptionHandler` 必须返回 `ResponseEntity<ProblemDetail>` 否则状态码变 200；全局 ObjectMapper 的 NON_DEFAULT 序列化会吞掉 properties 里的 traceId。

来源：[Baeldung — Spring Boot ProblemDetail](https://www.baeldung.com/spring-boot-return-errors-problemdetail)

### 11. Resilience4j 容错封装 + HTTP 客户端拦截器

**解决问题**：下游抖动级联故障；重试/熔断策略散落各处；service task 内同步调用外部系统无保护。

**实现要点**：
- `resilience4j-spring-boot3` starter，注解式 `@CircuitBreaker/@Retry/@RateLimiter/@TimeLimiter` + YAML 按实例配置；`resilience4j-micrometer` 自动出指标；
- 坑：叠加注解时 Spring AOP 默认顺序可能使 @Retry 在 @CircuitBreaker 外层，需显式指定 order；
- correlationId 透传：RestClient 用 `ClientHttpRequestInterceptor`、WebClient 用 `ExchangeFilterFunction`、Feign 用 `RequestInterceptor` 统一注入 traceId/entity 头；
- **与流程的协作纪律**：下游熔断/重试放在 delegate 内部；流程层的重试交给 async executor（组件 4），两层重试不可叠加放大（流程重试 × HTTP 重试 × 次数要算总账）。

来源：[Resilience4j 官方仓库](https://github.com/resilience4j/resilience4j)

### 12. Transactional Outbox 组件（流程事件可靠外发）

**解决问题**：Flowable 流程状态变更 + 发 MQ 的双写不一致；service task 里直接发消息在事务回滚后产生脏消息。

**实现要点**：
- 业务表与 outbox 表同一本地事务写入；Relay 用轮询发布或 Debezium CDC（Outbox Event Router SMT）；
- 与 Flowable 的结合点：在组件 1 的全局监听器中，仅处理 `TRANSACTION_COMMITTED` 生命周期事件（`isFireOnTransactionLifecycleEvent` + `getOnTransaction`）写 outbox——保证只在流程事务提交后落事件；
- **消费端必须幂等**（at-least-once 语义）：messageId 去重表唯一键或 Redis SETNX；
- 流程启动防重：`startProcessInstanceByKey(key, businessKey, vars)` 的 businessKey 加唯一约束，天然幂等键。

来源：[microservices.io — Transactional outbox pattern](https://microservices.io/patterns/data/transactional-outbox.html)

### 13. ShedLock 与幂等防重组件

**实现要点**：
- ShedLock 7.x 支持 Spring Boot 3.5/4.x：`@EnableSchedulerLock(defaultLockAtMostFor)` + `@SchedulerLock(name, lockAtMostFor, lockAtLeastFor)`；锁存储 20+ 种（JDBC 推荐 `usingDbTime()` 防时钟漂移；严格分库场景用 Redis/K8s Lease）；抢不到锁即跳过不等待；`lockAtMostFor` 必须远大于正常耗时（节点宕机安全网）；`LockAssert.assertLocked()` 防 AOP 配置错误；`shedlock-micrometer` 出指标。典型用途：outbox relay、死信扫描、积压统计；
- 注意：ShedLock 是锁不是分布式调度器；其 PROXY_SCHEDULER 模式与 OTel 的 TaskScheduler 包装冲突，用默认 PROXY_METHOD。

来源：[ShedLock 官方仓库](https://github.com/lukas-krecan/ShedLock)

---

## 五、工程化：企业内部 starter 骨架

### 14. platform starter 的标准做法（Spring 官方规范）

**实现要点**（全部来自 Spring Boot 官方文档，已核实）：
1. 双模块：`xxx-spring-boot-autoconfigure`（逻辑）+ `xxx-spring-boot-starter`（纯依赖聚合空 jar）；
2. 注册文件：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（2.7+，不再用 spring.factories）；
3. 条件组合：`@AutoConfiguration` + `@ConditionalOnClass` + `@ConditionalOnProperty(prefix="platform.xxx", name="enabled")` + `@ConditionalOnMissingBean`（允许业务覆盖）；
4. 配置属性独立命名空间 `@ConfigurationProperties` + `spring-boot-autoconfigure-processor` 生成 metadata；禁止占用 `spring.*`/`server.*`；
5. 测试用 `ApplicationContextRunner` + `FilteredClassLoader` 覆盖各条件分支。

来源：[Spring Boot 官方 — Developing auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)

---

## 六、开源脚手架对照（成熟度参考）

| 项目 | 公共模块划分 | 技术栈 | 借鉴价值 |
| --- | --- | --- | --- |
| RuoYi-Flowable(-Plus) | ruoyi-common / framework / flowable 模块内分层，未抽独立 starter | Flowable 6.7 + Boot 2.x/JDK8 | 模块划分思路可参考；栈老旧，可观测性/容错/outbox 缺失 |
| JeecgBoot | 低代码平台，监控靠 SkyWalking/Prometheus 外挂 | Boot 3.x，36K+ star | 分层思路可借鉴；横切组件与平台耦合深，提取成本高 |
| yudao（芋道） | bpm 模块内沉淀了事件桥接、候选人策略、EL 函数等组件 | Boot 3.x + Flowable 7.x | 组件级实现的最近参照（BpmActivityBehaviorFactory、FlowableFunctionDelegate） |
| Flowable 官方 | 提供扩展点（EngineConfigurationConfigurer、Event Logging、AsyncExecutor）而非成品组件 | — | 扩展点挂接标准，企业需在其上自建 starter |

结论：**开源脚手架沉淀的是"划分思路"，官方沉淀的是"扩展点"，成品级公共组件需要按本清单自建**——这正与多实体文档的 platform-core 定位吻合。

---

## 七、组件分层建议（映射到 platform-core）

```
platform-core
├── observability/        # 组件 1/9：全局监听器 + MDC/trace 透传 + 流程指标
├── delegate/             # 组件 3：JavaDelegate/TaskListener/ExecutionListener 基类
├── jobops/               # 组件 4：死信扫描告警/复活（ShedLock 保护）
├── error/                # 组件 5/10：BpmnError 分类 + ProblemDetail 体系
├── integration/          # 组件 11/12：Resilience4j 封装、correlationId 拦截器、Outbox
├── candidate/            # 组件 6：候选人策略接口 + 默认实现
└── variable/             # 组件 8：JSON 变量类型 + EL 函数
```

分发形态：按组件 14 的规范打成 `platform-flowable-spring-boot-starter`，实体模块引入即用、`@ConditionalOnMissingBean` 允许实体覆盖个别策略（如候选人规则有实体差异时）。
