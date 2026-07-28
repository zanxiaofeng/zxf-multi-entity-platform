# Flowable 公共组件落地设计

> **日期**：2026-07-28
> **状态**：已确认，待编写实施计划
> **深度定位**：示范级——每个组件一个最小可运行用例 + demo 触发条件，展示设计意图和核心 API 用法，与现有已落地组件（1/2/4/5/7/8/10）的"示范级"风格一致

---

## 1. 背景与范围

### 1.1 来源

设计文档 7.7《Flowable 公共组件沉淀》列出了 14 类组件作为 platform-core 的"目标职责清单（演进参考，路线图外）"。当前工程已落地 8 个（1/2/4 调优部分/5/7/8/9/10），本 spec 覆盖剩余 **8 个**：

| 类型 | 组件 |
|------|------|
| 补全 | 3（delegate 基类横切能力）、4（死信运维 API） |
| 新增 | 6（候选人策略）、11（Resilience4j）、12（Outbox）、13（ShedLock）、14（starter 骨架） |

### 1.2 不含

- 已落地的组件 1/2/5/7/8/9/10（不重复设计）
- 组件 4 已落地的引擎级调优 + 节点级重试 + 死信路径示范（不重复设计，只补死信运维 API）

---

## 2. 整体架构

### 2.1 包结构

保持现有六边形分包（`infrastructure.engine/`、`infrastructure.observation/` 等），新增两个包：

| 新包 | 归属层 | 内容 |
|------|--------|------|
| `infrastructure.scheduling` | 基础设施 | 组件 13 ShedLock 配置 |
| `infrastructure.integration` | 基础设施 | 组件 11 Resilience4j + 组件 12 Outbox |

这两个包需注册到 `ArchitectureGuardTest` 的 `onionArchitecture()` adapter 声明中。

### 2.2 新增 Maven 模块（组件 14）

```
multi-entity-platform/
├── platform-core/                       ← 现有，不变
├── platform-flowable-autoconfigure/     ← 新：@AutoConfiguration 逻辑
├── platform-flowable-starter/           ← 新：纯依赖聚合空 jar
├── entity-alpha/                        ← 现有，不变
├── entity-beta/                         ← 现有，不变
└── app/                                 ← 现有，pom 加 starter 依赖
```

root pom `<modules>` 和 `<dependencyManagement>` 追加两个新模块。

### 2.3 新增依赖

| 依赖 | 引入位置 | 组件 | 备注 |
|------|---------|------|------|
| `shedlock-spring` + `shedlock-provider-jdbc` | platform-core pom | 13 | 版本兼容性实施时确认；若 SB4 不兼容退用 `shedlock-core` 手动配置 |
| `resilience4j-circuitbreaker` + `resilience4j-retry` | platform-core pom | 11 | 核心库，不依赖 spring-boot autoconfigure，SB4 无关 |

> 组件 11 测试用 `MockRestServiceServer`（spring-test 内置，`spring-boot-starter-test` 已包含），无需额外引入 WireMock。

### 2.4 实施批次与依赖顺序

```
批次 1（引擎侧补全，无新依赖）：
  组件 3 — delegate 基类横切能力
  组件 4 — 死信运维 API

批次 2（引擎侧新增，无新依赖）：
  组件 6 — 候选人策略

批次 3（集成侧新增，引入新依赖）：
  组件 13 — ShedLock（先落地，保护 ↓）
  组件 12 — Outbox（依赖组件 1 已落地 + 组件 13）
  组件 11 — Resilience4j（需要下游调用场景）

批次 4（工程化）：
  组件 14 — starter 骨架
```

---

## 3. 组件 3 — delegate 基类横切能力（补全）

### 3.1 现状

`EntityContextAwareDelegate`（`infrastructure.engine`）只有上下文重建（execute/doExecute 模板方法骨架）。需补全：统一执行日志、耗时统计、异常分类。

### 3.2 设计

增强 `execute()` 方法，所有 `doExecute()` 调用统一经私有方法 `executeWithObservation()` 包裹：

```
execute(DelegateExecution)
  ├── 上下文重建（现有逻辑不变：同步路径直接执行 / async 路径从流程变量重建）
  └── executeWithObservation(execution)
        ├── 入口日志：delegate 名 + orderId + processInstanceId
        ├── Timer.start()（Micrometer Timer "flowable.delegate.execution"，tag=delegate 名 + entity）
        ├── doExecute(execution)
        ├── 成功 → stop Timer + 出口日志
        ├── catch BpmnError → WARN 日志 + 传播（业务错误走分支，不重试）
        └── catch Exception → ERROR 日志 + 传播（技术异常走重试→死信）
```

### 3.3 关键改动

- 基类构造器注入 `MeterRegistry`（protected final 字段 + protected 构造器）
- 三个子类（`SendNotificationDelegate`、`AlphaRiskCheckDelegate`、`BetaAuditExtraDelegate`）各改为手写构造器调用 `super(meterRegistry)`（Lombok `@RequiredArgsConstructor` 在继承 + super 场景下不支持自动转发，手写一行可控）
- 异常分类不吞异常：基类只做观测（日志 + 计时），Flowable 按自身机制处理重试/分支
- BpmnError catch 在 Exception catch 之前（BpmnError extends FlowableException extends RuntimeException）

### 3.4 测试

- `EntityContextAwareDelegateTest` 增强：验证 Timer 注册、BpmnError 记 WARN 后传播、技术异常记 ERROR 后传播
- 现有 e2e 不变（基类行为对 doExecute 透明）

---

## 4. 组件 4 — 死信运维 API（补全）

### 4.1 现状

引擎级调优 + 节点级 `failedJobRetryTimeCycle` + 死信路径示范（`NotificationFailedException` → 重试 → 死信表）已落地。需补全：死信 Job 扫描 / 指标 / 复活。

### 4.2 设计

| 项 | 内容 |
|----|------|
| 类 | `infrastructure.engine.DeadLetterJobOperations`（`@Component`） |
| API | `List<DeadLetterJobSummary> list()` — 扫描死信 Job（id / processInstanceId / exceptionMessage / retries）；`void retry(String jobId)` — `managementService.moveDeadLetterJobToExecutableJob(jobId, 1)` 复活 |
| 指标 | Micrometer Gauge `flowable.deadletter.jobs.count`（值 = `managementService.createDeadLetterJobQuery().count()`，entity tag 由 MetricsConfig commonTags 注入） |
| 内部 record | `DeadLetterJobSummary`（id / processInstanceId / exceptionMessage / retries） |

### 4.3 测试

- e2e 增强（`AlphaOrderApiEndToEndTest`）：mock `NotificationPort` 抛异常触发死信 → await 死信 Job 出现 → 断言 Gauge > 0 + `list()` 返回该 Job + `retry()` 后 Job 转为可执行
- 触发方式：`@MockitoBean NotificationPort` + `doThrow(NotificationFailedException)` 在死信测试方法中 stub（正常路径测试方法默认 doNothing）
- 死信重试耗尽需要等待时间（R3/PT5S = 3 次 × 5 秒间隔 ≈ 10-15 秒），awaitility atMost 设 30 秒

---

## 5. 组件 6 — 候选人策略

### 5.1 方案

文档建议 `setActivityBehaviorFactory`（继承 `DefaultActivityBehaviorFactory`），示范级用**全局事件监听器在 `TASK_CREATED` 时分配**——复用组件 1 的监听器机制，更简洁。注释标注 `ActivityBehaviorFactory` 为替代方案。

### 5.2 策略接口与实现（与 `PricingPolicy` 同套 SPI）

```
domain.port.TaskAssignmentRule          ← 纯 Java 接口（无 Flowable 依赖）
  ├── EntityType supports()
  └── List<String> candidatesFor(String taskDefinitionKey)

entity-alpha/adapter.AlphaTaskAssignmentRule   ← @ForEntity(ALPHA)
  alphaApproveL1 → ["alpha-manager-1"]
  alphaApproveL2 → ["alpha-manager-2"]
  alphaApproveL3 → ["alpha-director"]

entity-beta/adapter.BetaTaskAssignmentRule     ← @ForEntity(BETA)
  betaApproveL1..L5 → ["beta-approver-N"]
```

### 5.3 引擎侧监听器

```
infrastructure.engine.TaskAssignmentListener implements FlowableEventListener
  ├── onEvent: 仅处理 TASK_CREATED
  │   ├── 从 FlowableEngineEntityEvent.getEntity() 取 Task
  │   ├── 取 taskDefinitionKey → 调 TaskAssignmentRule.candidatesFor(key)
  │   └── taskService.addCandidateUser(taskId, candidate) 逐个分配
  ├── isFailOnException → false（同组件 1 纪律）
  └── 注入 Optional<TaskAssignmentRule>（无实现时安全跳过）
```

注册：`FlowableJobContextConfig.eventListenerConfigurer` 的 `setEventListeners` 列表加入 `TaskAssignmentListener`。

### 5.4 守护

| 类型 | 内容 |
|------|------|
| 契约测试 | `TaskAssignmentRuleContractTest`（platform-core test-jar），验证 `supports()` 与 `@ForEntity` 一致——与 `PricingPolicyContractTest` 同构 |
| ArchUnit | 实体模块 `ArchitectureGuardTest` 加规则：`TaskAssignmentRule` 实现必须 `@ForEntity` 限定 |
| e2e | `AlphaOrderApiEndToEndTest` / `BetaOrderApiEndToEndTest` 加测试：下单后查 active task 的 `IdentityLinks`，断言含正确候选人 |

---

## 6. 组件 13 — ShedLock

### 6.1 设计

| 项 | 内容 |
|----|------|
| 依赖 | `shedlock-spring` + `shedlock-provider-jdbc`（版本兼容性实施时确认） |
| Flyway | `common/V7__shedlock.sql`：`shedlock` 表（name VARCHAR(64) PK / lock_until TIMESTAMP / locked_at TIMESTAMP / locked_by VARCHAR(255)） |
| 配置 | `infrastructure.scheduling.ShedLockConfig`：`@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")` + `@EnableScheduling` + `JdbcLockProvider(dataSource)` bean |
| 用途 | 被组件 12 Outbox relay 和组件 4 死信扫描 `@Scheduled` 方法使用 |

### 6.2 兼容性风险

ShedLock 5.x 基于 Spring Boot 3。SB4 兼容性实施时验证：
- 若 `shedlock-spring` 5.x+ 兼容 SB4 → 直接用
- 若不兼容 → 退用 `shedlock-core` 手动配置 `LockProvider`（JDBC 实现）

### 6.3 测试

- `ShedLockConfigTest`（单元测试）：验证 `LockProvider` bean 创建
- 集成验证由组件 12 Outbox relay 的 e2e 覆盖（relay 定时执行 → 验证 outbox 事件被发布）

---

## 7. 组件 12 — Transactional Outbox

### 7.1 数据层

```
common/V8__outbox_event.sql
  outbox_event(
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE
  )

domain.model.OutboxEvent              ← record（id / aggregateType / aggregateId / eventType / payload / createdAt / publishedAt）
domain.port.OutboxRepository           ← save(event) / findUnpublished(limit) / markPublished(id)
infrastructure.persistence.OutboxEventJpaRepository + OutboxEventJpaAdapter
```

### 7.2 写入点

`OrderApplicationService.create()` 事务内追加一行 outbox 事件（与业务表同事务，回滚则不留事件）：

```
repository.save(order)
→ outboxRepository.save(new OutboxEvent("ORDER", orderId.value(), "ORDER_CREATED", payload, now, null))
```

注释说明：文档提到"在全局监听器中处理 `TRANSACTION_COMMITTED` 写 outbox"——采用标准应用层事务内写入（outbox 模式常规做法），注释标注 Flowable 事件监听器方式为替代方案。

### 7.3 Relay

```
infrastructure.integration.OutboxRelay
  @Scheduled(fixedDelay = 5000)
  @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT4M", lockAtLeastFor = "PT5S")
  relay()
    ├── 手动设置 EntityContext（调度线程无请求上下文，从 PlatformProperties 取）+ MDC
    ├── findUnpublished(10) → 逐条 log.info("outbox 发布 eventType={} aggregateId={}") 模拟 MQ 发送
    ├── markPublished(id)
    └── finally EntityContext.clear() + MDC.clear()
```

### 7.4 测试

- e2e 增强：下单后 await outbox 事件被发布（`published_at` 非空，relay fixedDelay=5s，atMost 设 15s）

---

## 8. 组件 11 — Resilience4j 容错封装 + HTTP 拦截器

### 8.1 版本策略

不依赖 `resilience4j-spring-boot`（SB4 autoconfigure 兼容性未确认），改用**核心库手动配置**（`resilience4j-circuitbreaker` + `resilience4j-retry`，纯 JDK 无 Spring 依赖，SB4 无关）。

### 8.2 层次结构

```
domain.port.NotificationPort               ← void send(orderId, processInstanceId)

infrastructure.integration.NotificationClient implements NotificationPort
  ├── RestClient + CircuitBreaker + Retry（程序式 Decorators.ofCallable 链）
  ├── 下游 POST /api/v1/notifications
  └── 失败传播给 delegate → Flowable Job 重试（两层重试不叠加：HTTP Retry 在 CircuitBreaker 内，
      次数与 failedJobRetryTimeCycle 算总账，注释说明）

infrastructure.integration.RestClientConfig
  ├── RestClient bean（baseUrl 从 platform.notification.base-url 读）
  └── CorrelationIdInterceptor：ClientHttpRequestInterceptor 注入 X-Trace-Id + X-Entity 头

infrastructure.integration.ResilienceConfig
  ├── CircuitBreakerRegistry + CircuitBreaker("notification")
  └── RetryRegistry + Retry("notification", maxAttempts=3, waitDuration=500ms)
```

### 8.3 SendNotificationDelegate 增强

从"只写审计"变为"先调 NotificationPort 下游通知 → 成功后写审计"：

```
doExecute
  ├── notificationClient.send(orderId, processInstanceId)   ← 新增
  └── audit.record("APPROVAL_NOTIFICATION", ...)             ← 现有（send 成功后才执行）
```

**移除现有 "888 前缀" demo 触发条件**——失败源统一为 NotificationClient 的下游调用（下游 500 / 超时 → `NotificationFailedException` → 基类记 ERROR → Flowable Job 重试 → 死信）。这消除了两个失败源并存的歧义，且 orderId 自增（从 1 开始）时 888 前缀实际永不触发。

`NotificationFailedException`（M1 修复引入）保留——`NotificationClient` 失败时抛出它。

### 8.4 配置

application.yaml（公共）追加：
```yaml
platform:
  notification:
    base-url: http://localhost:8081   # 占位；测试中由 WireMock / MockRestServiceServer 覆盖
```

### 8.5 测试

- **单元测试** `NotificationClientTest`：MockRestServiceServer mock RestClient → 验证 Retry（首次 500 + 第二次 200）、CircuitBreaker（连续失败后打开）、CorrelationIdInterceptor（请求头含 X-Trace-Id）
- **e2e mock 策略**：`AlphaOrderApiEndToEndTest` / `BetaOrderApiEndToEndTest` 加 `@MockitoBean NotificationPort`——正常路径测试方法默认 `doNothing()`（通知成功）；死信路径测试方法（组件 4）`doThrow(NotificationFailedException)` 触发失败 → 重试 → 死信。SB4 `@MockitoBean` 在每个测试方法前自动重置 stubbing

---

## 9. 组件 14 — 企业内部 starter 骨架

### 9.1 双模块结构

```
platform-flowable-autoconfigure/
  src/main/java/com/zxf/platform/flowable/autoconfigure/
    FlowableHealthAutoConfiguration.java
    FlowableHealthProperties.java
    FlowableEngineHealthIndicator.java
  src/main/resources/
    META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  src/test/java/
    FlowableHealthAutoConfigurationTest.java

platform-flowable-starter/
  pom.xml   ← 纯依赖聚合，无 src
```

### 9.2 AutoConfiguration 要素

| 要素 | 实现 |
|------|------|
| `@AutoConfiguration` | `FlowableHealthAutoConfiguration` |
| `@ConditionalOnClass` | `RuntimeService.class` 在 classpath 时激活 |
| `@ConditionalOnProperty` | `platform.flowable.health.enabled`（matchIfMissing=true，默认启用） |
| `@ConditionalOnMissingBean` | 允许业务覆盖 `flowableHealthIndicator` |
| `@ConfigurationProperties` | `FlowableHealthProperties`（record，`platform.flowable.health.*`） |
| imports 注册 | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |

### 9.3 HealthIndicator

`FlowableEngineHealthIndicator extends AbstractHealthIndicator`：查询活跃流程实例数，`/actuator/health` 输出 `flowable: { status: UP, activeProcessInstances: N }`。

### 9.4 app 集成

app pom 加 `platform-flowable-starter` 依赖 → `FlowableHealthAutoConfiguration` 自动发现 → `/actuator/health` 包含 flowable 健康检查。与 platform-core 的 `FlowableJobContextConfig`（手工引擎配置）职责不冲突。

### 9.5 测试

`FlowableHealthAutoConfigurationTest`（ApplicationContextRunner）覆盖条件分支：
- 有 `RuntimeService` + 默认 enabled → 注册 HealthIndicator
- 无 `RuntimeService`（`FilteredClassLoader`）→ 不注册
- `platform.flowable.health.enabled=false` → 不注册
- 已有同名 bean → 不注册（`@ConditionalOnMissingBean`）

e2e 增强：`AlphaOrderApiEndToEndTest` 断言 `/actuator/health` 响应含 `flowable` 组件。

---

## 10. ArchUnit / Enforcer 影响

### 10.1 ArchUnit

`platform-core/ArchitectureGuardTest` 的 `onionArchitecture()` adapter 声明追加：
```java
.adapter("scheduling", "..infrastructure.scheduling..")
.adapter("integration", "..infrastructure.integration..")
```

实体模块 `ArchitectureGuardTest` 追加规则：
```java
@ArchTest
static final ArchRule 候选人策略实现必须限定ForEntity = classes()
        .that().implement(TaskAssignmentRule.class)
        .should().beAnnotatedWith(ForEntity.class);
```

### 10.2 Maven Enforcer

- root pom `<modules>` 追加 `platform-flowable-autoconfigure` + `platform-flowable-starter`
- root pom `<dependencyManagement>` 追加两个新模块坐标
- `platform-flowable-autoconfigure` pom 禁止依赖实体模块（与 platform-core 同规则）
- Enforcer `dependencyConvergence` 对新依赖（shedlock / resilience4j）验证无版本冲突

---

## 11. 新增文件清单

### platform-core 主代码

| 文件 | 组件 |
|------|------|
| `infrastructure/engine/EntityContextAwareDelegate.java`（增强） | 3 |
| `infrastructure/engine/DeadLetterJobOperations.java`（新建） | 4 |
| `infrastructure/engine/DeadLetterJobSummary.java`（新建 record） | 4 |
| `domain/port/TaskAssignmentRule.java`（新建） | 6 |
| `infrastructure/engine/TaskAssignmentListener.java`（新建） | 6 |
| `infrastructure/scheduling/ShedLockConfig.java`（新建） | 13 |
| `domain/model/OutboxEvent.java`（新建） | 12 |
| `domain/port/OutboxRepository.java`（新建） | 12 |
| `infrastructure/persistence/OutboxEventJpaRepository.java`（新建） | 12 |
| `infrastructure/persistence/OutboxEventJpaAdapter.java`（新建） | 12 |
| `infrastructure/integration/OutboxRelay.java`（新建） | 12 |
| `domain/port/NotificationPort.java`（新建） | 11 |
| `infrastructure/integration/NotificationClient.java`（新建） | 11 |
| `infrastructure/integration/RestClientConfig.java`（新建） | 11 |
| `infrastructure/integration/CorrelationIdInterceptor.java`（新建） | 11 |
| `infrastructure/integration/ResilienceConfig.java`（新建） | 11 |
| `infrastructure/engine/SendNotificationDelegate.java`（增强） | 11 |
| `application/OrderApplicationService.java`（增强：写 outbox） | 12 |

### platform-core 测试

| 文件 | 组件 |
|------|------|
| `infrastructure/engine/EntityContextAwareDelegateTest.java`（增强） | 3 |
| `infrastructure/engine/DeadLetterJobOperationsTest.java`（新建） | 4 |
| `domain/port/TaskAssignmentRuleContractTest.java`（新建 test-jar 基类） | 6 |
| `infrastructure/integration/NotificationClientTest.java`（新建） | 11 |

### platform-core Flyway

| 文件 | 组件 |
|------|------|
| `common/V7__shedlock.sql`（新建） | 13 |
| `common/V8__outbox_event.sql`（新建） | 12 |

### entity-alpha

| 文件 | 组件 |
|------|------|
| `adapter/AlphaTaskAssignmentRule.java`（新建） | 6 |
| `adapter/AlphaTaskAssignmentRuleContractTest.java`（新建） | 6 |
| `adapter/AlphaRiskCheckDelegate.java`（增强：super 构造器） | 3 |

### entity-beta

| 文件 | 组件 |
|------|------|
| `adapter/BetaTaskAssignmentRule.java`（新建） | 6 |
| `adapter/BetaTaskAssignmentRuleContractTest.java`（新建） | 6 |
| `adapter/BetaAuditExtraDelegate.java`（增强：super 构造器） | 3 |

### app 测试

| 文件 | 组件 |
|------|------|
| `AlphaOrderApiEndToEndTest.java`（增强：候选人 + 死信 + outbox + health） | 6/4/12/14 |
| `BetaOrderApiEndToEndTest.java`（增强：同上对称） | 6/4/12/14 |

### platform-flowable-autoconfigure（新模块）

| 文件 | 组件 |
|------|------|
| `FlowableHealthAutoConfiguration.java` | 14 |
| `FlowableHealthProperties.java` | 14 |
| `FlowableEngineHealthIndicator.java` | 14 |
| `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 14 |
| `FlowableHealthAutoConfigurationTest.java` | 14 |

### platform-flowable-starter（新模块）

| 文件 | 组件 |
|------|------|
| `pom.xml`（纯依赖聚合） | 14 |

---

## 12. 风险与待确认项

| # | 风险 | 缓解 |
|---|------|------|
| 1 | ShedLock SB4 兼容性 | 实施时验证；不兼容则退 `shedlock-core` 手动配置 |
| 2 | Resilience4j 核心库版本与 JDK 21 兼容性 | 实施时验证最新稳定版 |
| 3 | `@ConfigurationProperties` metadata 生成（autoconfigure 模块） | 实施时决定是否引入 `spring-boot-autoconfigure-processor`（示范级可省略，注释说明） |
| 4 | 死信 Job 重试耗尽等待时间（R3/PT5S ≈ 10-15s）导致 e2e 测试变慢 | awaitility atMost 设 30s；或测试中调小 `failedJobWaitTime` |
