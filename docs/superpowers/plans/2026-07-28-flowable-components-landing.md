# Flowable 公共组件落地实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 Flowable 公共组件调研文档中的全部 14 个组件（8 个待实施），使 platform-core 成为完整的 Flowable 公共组件示范。

**Architecture:** 保持六边形分包，新增 `infrastructure.scheduling` 和 `infrastructure.integration` 两个包 + `platform-flowable-autoconfigure` / `platform-flowable-starter` 两个 Maven 模块。组件间通过 domain.port 接口解耦，实体模块按 `@ForEntity` SPI 套路提供差异实现。

**Tech Stack:** Spring Boot 4.1 / JDK 21 / Flowable 8.0 / Micrometer / Resilience4j 核心库 / ShedLock / H2

**Spec:** `docs/superpowers/specs/2026-07-28-flowable-components-landing-design.md`

## Global Constraints

- 构建验证：`mvn -B verify -Palpha` 与 `mvn -B verify -Pbeta` 双装配全绿
- 依赖单向：`entity-* → platform-core`，`app → platform-core`；core 禁依赖实体模块（Enforcer）
- 扩展点实现统一 `@ForEntity(EntityType.ALPHA|BETA)` 限定（ArchUnit 守护）
- delegate 一律继承 `EntityContextAwareDelegate`（实体模块 ArchUnit 强制）
- 日志用 `@Slf4j` + 占位符；异常对象作末参
- DTO / VO 全用 record；JPA Entity 用 `@Getter` + 手写 equals/hashCode
- Flyway 脚本 H2 兼容，已合入迁移不可改
- 测试方法名用中文（项目统一风格）
- app 测试按 `assembly.entity` 系统属性门控

---

## 批次 1：引擎侧补全（组件 3 + 4）

### Task 1: 组件 3 — delegate 基类横切能力

**Files:**
- Modify: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/engine/EntityContextAwareDelegate.java`
- Modify: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/engine/SendNotificationDelegate.java`
- Modify: `entity-alpha/src/main/java/com/zxf/platform/alpha/adapter/AlphaRiskCheckDelegate.java`
- Modify: `entity-beta/src/main/java/com/zxf/platform/beta/adapter/BetaAuditExtraDelegate.java`
- Test: `platform-core/src/test/java/com/zxf/platform/core/infrastructure/engine/EntityContextAwareDelegateTest.java`

**Interfaces:**
- Produces: `EntityContextAwareDelegate(MeterRegistry meterRegistry)` — protected 构造器；子类须 `super(meterRegistry)`

- [ ] **Step 1: 增强测试 — 验证 Timer 注册 + BpmnError 记 WARN + 技术异常记 ERROR**

在 `EntityContextAwareDelegateTest` 中追加三个测试方法。基类现在需要 `MeterRegistry`，已有测试中的匿名子类构造器要加参数：

```java
private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

private final EntityContextAwareDelegate delegate = new EntityContextAwareDelegate(meterRegistry) {
    @Override
    protected void doExecute(DelegateExecution execution) {
        seenContext.set(EntityContext.currentOrNull());
        seenMdc.set(MDC.get("entity"));
    }
};
```

新增测试：

```java
@Test
void 执行后注册耗时指标() {
    var execution = executionWithEntityVariable("BETA");
    delegate.execute(execution);

    assertThat(meterRegistry.find("flowable.delegate.execution").timers())
            .as("基类应注册 flowable.delegate.execution Timer")
            .hasSize(1);
}

@Test
void 技术异常记ERROR后传播() {
    var errorDelegate = new EntityContextAwareDelegate(meterRegistry) {
        @Override
        protected void doExecute(DelegateExecution execution) {
            throw new NotificationFailedException("下游不可达");
        }
    };
    var execution = executionWithEntityVariable("ALPHA");

    assertThatThrownBy(() -> errorDelegate.execute(execution))
            .isInstanceOf(NotificationFailedException.class);
    // Timer 仍记录了这次执行（含失败）
    assertThat(meterRegistry.find("flowable.delegate.execution").timers()).hasSize(1);
}
```

BpmnError 测试需要 flowable-engine 依赖（AlphaRiskCheckDelegate 已引入）。在 platform-core 测试中可直接 import `org.flowable.engine.delegate.BpmnError`（platform-core 已依赖 flowable-spring-boot-starter-process）：

```java
@Test
void bpmnError记WARN后传播不走重试路径() {
    var bpmnDelegate = new EntityContextAwareDelegate(meterRegistry) {
        @Override
        protected void doExecute(DelegateExecution execution) {
            throw new BpmnError("RISK_REJECTED", "风控拒绝");
        }
    };
    var execution = executionWithEntityVariable("ALPHA");

    assertThatThrownBy(() -> bpmnDelegate.execute(execution))
            .isInstanceOf(BpmnError.class);
}
```

- [ ] **Step 2: 运行测试验证失败**

```bash
mvn -B test -pl platform-core -Dtest=EntityContextAwareDelegateTest
```
Expected: 编译失败（构造器签名不匹配）

- [ ] **Step 3: 实现 — 增强基类**

`EntityContextAwareDelegate.java` 关键改动：

```java
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.BpmnError;

@Slf4j
public abstract class EntityContextAwareDelegate implements JavaDelegate {

    protected final MeterRegistry meterRegistry;

    protected EntityContextAwareDelegate(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ENTITY_VARIABLE 常量不变

    @Override
    public final void execute(DelegateExecution execution) {
        if (EntityContext.currentOrNull() != null) {
            executeWithObservation(execution);
            return;
        }
        if (!(execution.getVariable(ENTITY_VARIABLE) instanceof String entityName)) {
            executeWithObservation(execution);
            return;
        }
        EntityContext.set(EntityType.valueOf(entityName));
        MDC.put(EntityContext.MDC_KEY, entityName);
        try {
            executeWithObservation(execution);
        } finally {
            MDC.remove(EntityContext.MDC_KEY);
            EntityContext.clear();
        }
    }

    protected abstract void doExecute(DelegateExecution execution);

    private void executeWithObservation(DelegateExecution execution) {
        var delegateName = getClass().getSimpleName();
        var orderId = execution.getVariable("orderId");
        log.info("delegate 执行开始 name={} orderId={} processInstanceId={}",
                delegateName, orderId, execution.getProcessInstanceId());
        var sample = Timer.start(meterRegistry);
        try {
            doExecute(execution);
            sample.stop(Timer.builder("flowable.delegate.execution")
                    .tag("delegate", delegateName)
                    .tag("entity", entityTag())
                    .register(meterRegistry));
            log.info("delegate 执行完成 name={} orderId={}", delegateName, orderId);
        } catch (BpmnError e) {
            sample.stop(Timer.builder("flowable.delegate.execution")
                    .tag("delegate", delegateName)
                    .tag("entity", entityTag())
                    .tag("outcome", "bpmn-error")
                    .register(meterRegistry));
            log.warn("delegate 业务错误 name={} orderId={} errorCode={}", delegateName, orderId, e.getErrorCode());
            throw e;
        } catch (Exception e) {
            sample.stop(Timer.builder("flowable.delegate.execution")
                    .tag("delegate", delegateName)
                    .tag("entity", entityTag())
                    .tag("outcome", "error")
                    .register(meterRegistry));
            log.error("delegate 技术异常 name={} orderId={}", delegateName, orderId, e);
            throw e;
        }
    }

    private String entityTag() {
        var entity = EntityContext.currentOrNull();
        return entity != null ? entity.name() : "none";
    }
}
```

- [ ] **Step 4: 更新三个子类的构造器**

`SendNotificationDelegate`（已有 `AuditPort audit`）：

```java
public class SendNotificationDelegate extends EntityContextAwareDelegate {

    private final AuditPort audit;

    public SendNotificationDelegate(MeterRegistry meterRegistry, AuditPort audit) {
        super(meterRegistry);
        this.audit = audit;
    }
    // doExecute 不变
}
```
删除 `@RequiredArgsConstructor`，加 `import io.micrometer.core.instrument.MeterRegistry`。

`AlphaRiskCheckDelegate`（无额外依赖）：

```java
public class AlphaRiskCheckDelegate extends EntityContextAwareDelegate {

    public AlphaRiskCheckDelegate(MeterRegistry meterRegistry) {
        super(meterRegistry);
    }
    // doExecute 不变
}
```
删除 `@RequiredArgsConstructor`。

`BetaAuditExtraDelegate` 同理。

- [ ] **Step 5: 运行全部测试验证通过**

```bash
mvn -B test -pl platform-core -Dtest=EntityContextAwareDelegateTest
mvn -B verify -Palpha
mvn -B verify -Pbeta
```
Expected: 全绿（app 测试中 delegate 子类由 Spring 注入，MeterRegistry 自动装配）

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(flowable): 7.7 组件 3 delegate 基类横切能力（统一日志/计时/异常分类）

EntityContextAwareDelegate 增强为完整模板方法基类：
- 入口/出口执行日志（delegate 名 + orderId + processInstanceId）
- Micrometer Timer 耗时统计（tag=delegate/entity/outcome）
- BpmnError 记 WARN 后传播（业务错误走分支，不重试）
- 技术异常记 ERROR 后传播（走 Job 重试→死信）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 组件 4 — 死信运维 API

**Files:**
- Create: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/engine/DeadLetterJobSummary.java`
- Create: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/engine/DeadLetterJobOperations.java`
- Modify: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/engine/FlowableJobContextConfig.java`
- Test: `platform-core/src/test/java/com/zxf/platform/core/infrastructure/engine/DeadLetterJobOperationsTest.java`

**Interfaces:**
- Consumes: `org.flowable.engine.ManagementService`（Flowable 引擎内置）
- Produces: `DeadLetterJobOperations.list()` → `List<DeadLetterJobSummary>`；`DeadLetterJobOperations.retry(String jobId)`

- [ ] **Step 1: 写 DeadLetterJobSummary record**

```java
package com.zxf.platform.core.infrastructure.engine;

/** 死信 Job 摘要（组件 4 运维 API）：不含 businessKey，经 processInstanceId 关联业务。 */
public record DeadLetterJobSummary(
        String jobId,
        String processInstanceId,
        String exceptionMessage,
        int retries) {
}
```

- [ ] **Step 2: 写 DeadLetterJobOperations 测试**

单元测试用 Mockito mock `ManagementService` + `DeadLetterJobQuery` + `Job`：

```java
@ExtendWith(MockitoExtension.class)
class DeadLetterJobOperationsTest {

    @Mock
    private ManagementService managementService;

    @Test
    void 列表返回死信Job摘要() {
        var job = mock(org.flowable.job.api.DeadLetterJob.class);
        when(job.getId()).thenReturn("job-1");
        when(job.getProcessInstanceId()).thenReturn("pi-1");
        when(job.getExceptionMessage()).thenReturn("下游不可达");
        when(job.getRetries()).thenReturn(0);
        var query = mock(org.flowable.job.api.DeadLetterJobQuery.class);
        when(query.list()).thenReturn(List.of(job));
        when(managementService.createDeadLetterJobQuery()).thenReturn(query);

        var ops = new DeadLetterJobOperations(managementService);
        var result = ops.list();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().jobId()).isEqualTo("job-1");
        assertThat(result.getFirst().exceptionMessage()).isEqualTo("下游不可达");
    }

    @Test
    void 复活死信Job调用引擎API() {
        var ops = new DeadLetterJobOperations(managementService);
        ops.retry("job-1");
        verify(managementService).moveDeadLetterJobToExecutableJob("job-1", 1);
    }

    @Test
    void 计数委托引擎查询() {
        var query = mock(org.flowable.job.api.DeadLetterJobQuery.class);
        when(query.count()).thenReturn(3L);
        when(managementService.createDeadLetterJobQuery()).thenReturn(query);

        var ops = new DeadLetterJobOperations(managementService);
        assertThat(ops.count()).isEqualTo(3L);
    }
}
```

- [ ] **Step 3: 运行测试验证失败**

```bash
mvn -B test -pl platform-core -Dtest=DeadLetterJobOperationsTest
```
Expected: 编译失败（`DeadLetterJobOperations` 不存在）

- [ ] **Step 4: 实现 DeadLetterJobOperations**

```java
package com.zxf.platform.core.infrastructure.engine;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.flowable.engine.ManagementService;
import org.springframework.stereotype.Component;

/**
 * 死信 Job 运维 API（文档 7.7.1 组件 4 补全）：扫描 / 计数 / 复活。
 *
 * <p>死信 Job 不含 businessKey，经 processInstanceId 关联业务上下文。
 * 按异常类型分流：网络/IO 类自动复活、代码 bug 类修复后复活、非关键可删除（文档建议）。
 */
@Component
@RequiredArgsConstructor
public class DeadLetterJobOperations {

    private final ManagementService managementService;

    /** 扫描全部死信 Job，返回摘要列表。 */
    public List<DeadLetterJobSummary> list() {
        return managementService.createDeadLetterJobQuery().list().stream()
                .map(job -> new DeadLetterJobSummary(
                        job.getId(), job.getProcessInstanceId(),
                        job.getExceptionMessage(), job.getRetries()))
                .toList();
    }

    /** 死信 Job 计数（供 Micrometer Gauge 使用）。 */
    public long count() {
        return managementService.createDeadLetterJobQuery().count();
    }

    /** 复活死信 Job：转为可执行 Job 并设置重试次数为 1。 */
    public void retry(String jobId) {
        managementService.moveDeadLetterJobToExecutableJob(jobId, 1);
    }
}
```

- [ ] **Step 5: 注册死信 Job 计数 Gauge**

在 `FlowableJobContextConfig` 中追加一个 bean：

```java
@Bean
public io.micrometer.core.instrument.GenderBuilder deadLetterJobGauge(
        DeadLetterJobOperations ops) {
    // 实际写法：用 Gauge.builder 注册到 MeterRegistry
    return null; // 占位——实际见下方完整代码
}
```

实际实现用 `EngineConfigurationConfigurer` 不合适（那是引擎配置）。改为在 `FlowableJobContextConfig` 或独立的 config 中注册 Gauge。最简方式——在 `FlowableJobContextConfig` 中加 `@Bean MeterRegistryCustomizer` 或直接在类上加 `Gauge` 注册。

最干净的方式：新建一个 `@Bean` 方法返回 `Gauge` 并注册到 `MeterRegistry`：

在 `FlowableJobContextConfig` 追加（需要注入 `MeterRegistry` + `DeadLetterJobOperations`，但 Configurer bean 无法直接注入 ops）。

改为在 `DeadLetterJobOperations` 上用 `@PostConstruct` 注册 Gauge：

```java
// DeadLetterJobOperations 追加
private final MeterRegistry meterRegistry;

// 构造器改为：
public DeadLetterJobOperations(ManagementService managementService, MeterRegistry meterRegistry) {
    this.managementService = managementService;
    Gauge.builder("flowable.deadletter.jobs.count", this, DeadLetterJobOperations::count)
            .description("死信 Job 数量（非零即需人工介入）")
            .register(meterRegistry);
}
```

删除 `@RequiredArgsConstructor`，手写构造器（两个依赖）。更新测试构造器：`new DeadLetterJobOperations(managementService, new SimpleMeterRegistry())`。

- [ ] **Step 6: 运行测试验证通过**

```bash
mvn -B test -pl platform-core -Dtest=DeadLetterJobOperationsTest
mvn -B verify -Palpha
mvn -B verify -Pbeta
```
Expected: 全绿

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(flowable): 7.7 组件 4 死信运维 API（扫描/计数/复活）

DeadLetterJobOperations 提供死信 Job 运维能力：
- list() 扫描死信 Job 摘要（id/processInstanceId/exceptionMessage/retries）
- count() 死信计数，注册为 Micrometer Gauge flowable.deadletter.jobs.count
- retry(jobId) 复活死信 Job（moveDeadLetterJobToExecutableJob）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 批次 2：候选人策略（组件 6）

### Task 3: 组件 6 — 候选人策略

**Files:**
- Create: `platform-core/src/main/java/com/zxf/platform/core/domain/port/TaskAssignmentRule.java`
- Create: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/engine/TaskAssignmentListener.java`
- Modify: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/engine/FlowableJobContextConfig.java`
- Create: `platform-core/src/test/java/com/zxf/platform/core/domain/port/TaskAssignmentRuleContractTest.java`
- Create: `entity-alpha/src/main/java/com/zxf/platform/alpha/adapter/AlphaTaskAssignmentRule.java`
- Create: `entity-beta/src/main/java/com/zxf/platform/beta/adapter/BetaTaskAssignmentRule.java`
- Create: `entity-alpha/src/test/java/com/zxf/platform/alpha/adapter/AlphaTaskAssignmentRuleContractTest.java`
- Create: `entity-beta/src/test/java/com/zxf/platform/beta/adapter/BetaTaskAssignmentRuleContractTest.java`
- Modify: `entity-alpha/src/test/java/com/zxf/platform/alpha/ArchitectureGuardTest.java`
- Modify: `entity-beta/src/test/java/com/zxf/platform/beta/ArchitectureGuardTest.java`

**Interfaces:**
- Produces: `TaskAssignmentRule.supports()` → `EntityType`；`TaskAssignmentRule.candidatesFor(String taskDefinitionKey)` → `List<String>`

- [ ] **Step 1: 写 TaskAssignmentRule 接口**

```java
package com.zxf.platform.core.domain.port;

import com.zxf.platform.core.context.EntityType;
import java.util.List;

/**
 * 审批人分配策略（文档 7.7.1 组件 6）：按任务定义键返回候选人列表。
 *
 * <p>与 {@link PricingPolicy} 同套 SPI：实现类必须 {@code @ForEntity} 限定，
 * {@code supports()} 返回值与注解一致（契约测试守护）。
 * 文档建议的 {@code ActivityBehaviorFactory} 为替代方案——示范级用全局事件监听器更简洁。
 */
public interface TaskAssignmentRule {

    EntityType supports();

    List<String> candidatesFor(String taskDefinitionKey);
}
```

- [ ] **Step 2: 写契约测试基类**

```java
package com.zxf.platform.core.domain.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.ForEntity;
import org.junit.jupiter.api.Test;

/**
 * 候选人策略契约测试基类（文档 8.4）：与 {@link PricingPolicyContractTest} 同构。
 * platform-core test-jar 发布，实体模块继承即获得契约回归。
 */
public abstract class TaskAssignmentRuleContractTest {

    protected abstract TaskAssignmentRule rule();

    protected abstract EntityType expectedEntity();

    @Test
    void supports与所在模块实体一致() {
        assertThat(rule().supports()).isEqualTo(expectedEntity());
    }

    @Test
    void 激活注解声明的实体与supports一致() {
        var annotation = rule().getClass().getAnnotation(ForEntity.class);
        assertThat(annotation)
                .as("@ForEntity 必须标注，且 value 与 supports() 一致")
                .isNotNull();
        assertThat(annotation.value()).isEqualTo(rule().supports());
    }
}
```

- [ ] **Step 3: 写 AlphaTaskAssignmentRule + 契约测试**

```java
// entity-alpha/adapter/AlphaTaskAssignmentRule.java
package com.zxf.platform.alpha.adapter;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.ForEntity;
import com.zxf.platform.core.domain.port.TaskAssignmentRule;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Alpha 审批人分配（文档 7.7.1 组件 6 示范）：按审批层级硬编码候选人。 */
@Component
@ForEntity(EntityType.ALPHA)
public class AlphaTaskAssignmentRule implements TaskAssignmentRule {

    private static final Map<String, List<String>> CANDIDATES = Map.of(
            "alphaApproveL1", List.of("alpha-manager-1"),
            "alphaApproveL2", List.of("alpha-manager-2"),
            "alphaApproveL3", List.of("alpha-director"));

    @Override
    public EntityType supports() {
        return EntityType.ALPHA;
    }

    @Override
    public List<String> candidatesFor(String taskDefinitionKey) {
        return CANDIDATES.getOrDefault(taskDefinitionKey, List.of());
    }
}
```

```java
// entity-alpha/adapter/AlphaTaskAssignmentRuleContractTest.java
package com.zxf.platform.alpha.adapter;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.domain.port.TaskAssignmentRule;
import com.zxf.platform.core.domain.port.TaskAssignmentRuleContractTest;

class AlphaTaskAssignmentRuleContractTest extends TaskAssignmentRuleContractTest {

    @Override
    protected TaskAssignmentRule rule() {
        return new AlphaTaskAssignmentRule();
    }

    @Override
    protected EntityType expectedEntity() {
        return EntityType.ALPHA;
    }
}
```

BetaTaskAssignmentRule + 契约测试对称（`betaApproveL1`..`L5` → `["beta-approver-N"]`）。

- [ ] **Step 4: 写 TaskAssignmentListener**

```java
package com.zxf.platform.core.infrastructure.engine;

import com.zxf.platform.core.domain.port.TaskAssignmentRule;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;

/**
 * 候选人分配监听器（文档 7.7.1 组件 6）：TASK_CREATED 时按策略分配候选人。
 *
 * <p>替代 ActivityBehaviorFactory（文档建议）——示范级用全局事件监听器更简洁，
 * 复用组件 1 的监听器注册机制。分配失败不回滚业务（isFailOnException=false）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskAssignmentListener implements FlowableEventListener {

    private final TaskService taskService;
    private final Optional<TaskAssignmentRule> assignmentRule;

    @Override
    public void onEvent(FlowableEvent event) {
        if (event.getType() != FlowableEngineEventType.TASK_CREATED) {
            return;
        }
        if (assignmentRule.isEmpty()) {
            return;
        }
        if (!(event instanceof FlowableEngineEntityEvent entityEvent)) {
            return;
        }
        if (!(entityEvent.getEntity() instanceof Task task)) {
            return;
        }
        var candidates = assignmentRule.get().candidatesFor(task.getTaskDefinitionKey());
        candidates.forEach(candidate -> taskService.addCandidateUser(task.getId(), candidate));
        log.info("候选人已分配 taskId={} taskKey={} candidates={}", task.getId(), task.getTaskDefinitionKey(), candidates);
    }

    @Override
    public boolean isFailOnException() {
        return false;
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        return false;
    }

    @Override
    public String getOnTransaction() {
        return null;
    }
}
```

- [ ] **Step 5: 注册监听器到引擎**

修改 `FlowableJobContextConfig.eventListenerConfigurer`：

```java
@Bean
public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> eventListenerConfigurer(
        FlowableEngineEventListener metricsListener, TaskAssignmentListener assignmentListener) {
    return configuration -> configuration.setEventListeners(java.util.List.of(metricsListener, assignmentListener));
}
```

- [ ] **Step 6: 实体模块 ArchUnit 加规则**

`entity-alpha/ArchitectureGuardTest` + `entity-beta/ArchitectureGuardTest` 各追加：

```java
@ArchTest
static final ArchRule 候选人策略实现必须限定ForEntity = classes()
        .that().implement(TaskAssignmentRule.class)
        .should().beAnnotatedWith(ForEntity.class);
```

加 import：`import com.zxf.platform.core.domain.port.TaskAssignmentRule;`

- [ ] **Step 7: e2e 验证候选人分配**

`AlphaOrderApiEndToEndTest` 追加测试方法：

```java
@Test
void 审批任务创建后自动分配候选人() throws Exception {
    var result = mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"item\":\"widget\",\"quantity\":1}"))
            .andExpect(status().isCreated())
            .andReturn();
    String orderId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

    var instance = runtimeService.createProcessInstanceQuery()
            .processInstanceBusinessKey(orderId).singleResult();
    var task = taskService.createTaskQuery()
            .processInstanceId(instance.getId()).active().singleResult();

    var links = taskService.getIdentityLinksForTask(task.getId());
    assertThat(links)
            .anyMatch(link -> "candidate".equals(link.getType())
                    && "alpha-manager-1".equals(link.getUserId()));
}
```

Beta 对称（`beta-approver-1`）。

- [ ] **Step 8: 运行验证 + 提交**

```bash
mvn -B verify -Palpha
mvn -B verify -Pbeta
git add -A
git commit -m "feat(flowable): 7.7 组件 6 候选人策略（TaskAssignmentRule + TASK_CREATED 监听器）

- domain.port.TaskAssignmentRule 策略接口（与 PricingPolicy 同套 SPI）
- Alpha/Beta 实现按审批层级硬编码候选人（示范级）
- TaskAssignmentListener 全局监听 TASK_CREATED 自动分配
- 契约测试基类 + ArchUnit 守护 + e2e 验证

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 批次 3：集成侧（组件 13 → 12 → 11）

### Task 4: 组件 13 — ShedLock

**Files:**
- Modify: `platform-core/pom.xml`（加 shedlock 依赖）
- Create: `platform-core/src/main/resources/db/migration/common/V7__shedlock.sql`
- Create: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/scheduling/ShedLockConfig.java`
- Modify: `platform-core/src/test/java/com/zxf/platform/core/ArchitectureGuardTest.java`

**Interfaces:**
- Produces: `ShedLockConfig`（`@EnableSchedulerLock` + `@EnableScheduling` + `LockProvider` bean）

- [ ] **Step 1: 加依赖 + Flyway 脚本**

`platform-core/pom.xml` 追加：

```xml
<dependency>
    <groupId>io.github.mutexlabs</groupId>
    <artifactId>shedlock-spring</artifactId>
    <version>6.9.2</version>
</dependency>
<dependency>
    <groupId>io.github.mutexlabs</groupId>
    <artifactId>shedlock-provider-jdbc</artifactId>
    <version>6.9.2</version>
</dependency>
```

> 版本号实施时用 `mvn dependency:tree` 确认最新 SB4 兼容版。若 groupId 不对，搜索 Maven Central `shedlock-spring`。

`V7__shedlock.sql`：

```sql
-- ShedLock 锁表（组件 13）：per-entity 库内，demo 单实例不实际竞争
-- 生产环境多实例部署时保护定时任务防重
CREATE TABLE shedlock (
    name       VARCHAR(64) NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
```

- [ ] **Step 2: 写 ShedLockConfig**

```java
package com.zxf.platform.core.infrastructure.scheduling;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * ShedLock 定时任务选主（文档 7.7.2 组件 13）。
 *
 * <p>严格分库下 per-entity 库内 JDBC 锁存储可用；global 任务（跨实体共享）
 * 的锁存储需 Redis / K8s Lease（文档 5.2.6）。
 * demo 单实例不实际竞争，配置示范为主。
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return JdbcTemplateLockProvider.builder()
                .dataSource(dataSource)
                .tableName("shedlock")
                .build();
    }
}
```

- [ ] **Step 3: ArchUnit 注册新包**

`ArchitectureGuardTest` 的 `onionArchitecture()` 追加：

```java
.adapter("scheduling", "..infrastructure.scheduling..")
```

- [ ] **Step 4: 运行验证**

```bash
mvn -B verify -Palpha
mvn -B verify -Pbeta
```
Expected: 全绿（锁表由 Flyway 创建，LockProvider bean 装配）

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(flowable): 7.7 组件 13 ShedLock 定时任务选主

- shedlock-spring + shedlock-provider-jdbc 依赖
- V7__shedlock.sql 锁表（per-entity 库内）
- ShedLockConfig @EnableSchedulerLock + JdbcTemplateLockProvider

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: 组件 12 — Outbox

**Files:**
- Create: `platform-core/src/main/resources/db/migration/common/V8__outbox_event.sql`
- Create: `platform-core/src/main/java/com/zxf/platform/core/domain/model/OutboxEvent.java`
- Create: `platform-core/src/main/java/com/zxf/platform/core/domain/port/OutboxRepository.java`
- Create: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/persistence/OutboxEventJpaRepository.java`
- Create: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/persistence/OutboxEventJpaAdapter.java`
- Create: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/integration/OutboxRelay.java`
- Modify: `platform-core/src/main/java/com/zxf/platform/core/application/OrderApplicationService.java`

**Interfaces:**
- Produces: `OutboxRepository.save/findUnpublished/markPublished`；`OutboxRelay.relay()` (@Scheduled + @SchedulerLock)

- [ ] **Step 1: Flyway + 领域模型 + 端口**

`V8__outbox_event.sql`：

```sql
CREATE TABLE outbox_event (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    payload        VARCHAR(2000),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at   TIMESTAMP WITH TIME ZONE
);
```

`OutboxEvent.java`：

```java
package com.zxf.platform.core.domain.model;

import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/** Outbox 事件（文档 7.7.2 组件 12）：与业务表同事务写入，relay 轮询发布。 */
public record OutboxEvent(
        @Nullable Long id,
        String aggregateType,
        String aggregateId,
        String eventType,
        @Nullable String payload,
        OffsetDateTime createdAt,
        @Nullable OffsetDateTime publishedAt) {

    public OutboxEvent {
        Assert.hasText(aggregateType, "aggregateType 不能为空");
        Assert.hasText(aggregateId, "aggregateId 不能为空");
        Assert.hasText(eventType, "eventType 不能为空");
    }
}
```

`OutboxRepository.java`：

```java
package com.zxf.platform.core.domain.port;

import com.zxf.platform.core.domain.model.OutboxEvent;
import java.util.List;

/** Outbox 持久化端口（文档 7.7.2 组件 12）。 */
public interface OutboxRepository {

    void save(OutboxEvent event);

    List<OutboxEvent> findUnpublished(int limit);

    void markPublished(Long id);
}
```

- [ ] **Step 2: JPA 适配器**

`OutboxEventJpaRepository.java`（包私有 Spring Data 接口）：

```java
package com.zxf.platform.core.infrastructure.persistence;

import com.zxf.platform.core.domain.model.OutboxEvent;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL ORDER BY e.createdAt")
    List<OutboxEvent> findUnpublished(Pageable pageable);
}
```

> OutboxEvent 需加 JPA 注解。因为它是 record（不可变），用 `@Entity` 不行——record 不能作 JPA Entity（JPA 需要无参构造 + 可变字段）。

**改用 class** 而非 record（JPA 实体约束）。OutboxEvent 改为：

```java
package com.zxf.platform.core.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

@Entity
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    protected OutboxEvent() {}

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // getter（行为访问器风格）
    public Long id() { return id; }
    public String aggregateType() { return aggregateType; }
    public String aggregateId() { return aggregateId; }
    public String eventType() { return eventType; }
    public @Nullable String payload() { return payload; }
    public OffsetDateTime createdAt() { return createdAt; }
    public @Nullable OffsetDateTime publishedAt() { return publishedAt; }

    public void markPublished() {
        this.publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
```

`OutboxEventJpaAdapter.java`：

```java
package com.zxf.platform.core.infrastructure.persistence;

import com.zxf.platform.core.domain.model.OutboxEvent;
import com.zxf.platform.core.domain.port.OutboxRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxEventJpaAdapter implements OutboxRepository {

    private final OutboxEventJpaRepository repository;

    @Override
    public void save(OutboxEvent event) {
        repository.save(event);
    }

    @Override
    public List<OutboxEvent> findUnpublished(int limit) {
        return repository.findUnpublished(PageRequest.of(0, limit));
    }

    @Override
    public void markPublished(Long id) {
        repository.findById(id).ifPresent(OutboxEvent::markPublished);
    }
}
```

> `markPublished` 依赖 JPA dirty checking——`markPublished()` 修改 `publishedAt`，事务提交时自动 flush。但 `findById` + 修改在同一事务中才有效。relay 方法需要 `@Transactional`。

- [ ] **Step 3: 写 OutboxRelay**

```java
package com.zxf.platform.core.infrastructure.integration;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.PlatformProperties;
import com.zxf.platform.core.domain.port.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 轮询发布（文档 7.7.2 组件 12）：定时扫描未发布事件，模拟 MQ 发送。
 *
 * <p>生产替换为真实 MQ Sender。relay 由 ShedLock 保护防多实例重复发送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxRepository repository;
    private final PlatformProperties properties;

    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT4M", lockAtLeastFor = "PT5S")
    @Transactional
    public void relay() {
        EntityContext.set(properties.entity());
        MDC.put(EntityContext.MDC_KEY, properties.entity().name());
        try {
            var events = repository.findUnpublished(10);
            if (events.isEmpty()) {
                return;
            }
            events.forEach(event -> {
                log.info("outbox 发布 eventType={} aggregateId={}", event.eventType(), event.aggregateId());
                repository.markPublished(event.id());
            });
        } finally {
            MDC.clear();
            EntityContext.clear();
        }
    }
}
```

- [ ] **Step 4: OrderApplicationService 写 outbox**

注入 `OutboxRepository`，在 `create()` 中 `save` 后追加：

```java
private final OutboxRepository outboxRepository;

// create() 方法内，save(order) 后：
outboxRepository.save(new OutboxEvent("ORDER", saved.id().value(), "ORDER_CREATED", null));
```

- [ ] **Step 5: e2e 验证 outbox 发布**

`AlphaOrderApiEndToEndTest` 追加：

```java
@Test
void 下单后outbox事件被relay发布() throws Exception {
    mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"item\":\"widget\",\"quantity\":1}"))
            .andExpect(status().isCreated());

    // relay fixedDelay=5s，等 relay 扫描并发布
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
        // outbox 表中应有已发布事件（published_at 非空）
        // 通过 JPA 查询验证——需要注入 OutboxRepository 或直接 JDBC
    });
}
```

> 验证方式：注入 `OutboxRepository`，查 `findUnpublished(1)` 为空（已被 relay 发布）。或直接 JDBC 查 `published_at IS NOT NULL`。

- [ ] **Step 6: 运行验证 + 提交**

```bash
mvn -B verify -Palpha
mvn -B verify -Pbeta
git add -A
git commit -m "feat(flowable): 7.7 组件 12 Transactional Outbox（事件可靠外发）

- outbox_event 表 + OutboxEvent JPA 实体 + OutboxRepository 端口
- OrderApplicationService 事务内写 outbox（与业务表同事务）
- OutboxRelay @Scheduled + @SchedulerLock 轮询发布（log 模拟 MQ）
- e2e 验证事件被发布

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 组件 11 — Resilience4j + HTTP 拦截器

**Files:**
- Modify: `platform-core/pom.xml`（加 resilience4j 依赖 + spring-boot-starter-restclient）
- Create: `platform-core/src/main/java/com/zxf/platform/core/domain/port/NotificationPort.java`
- Create: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/integration/NotificationClient.java`
- Create: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/integration/RestClientConfig.java`
- Create: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/integration/CorrelationIdInterceptor.java`
- Create: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/integration/ResilienceConfig.java`
- Modify: `platform-core/src/main/java/com/zxf/platform/core/infrastructure/engine/SendNotificationDelegate.java`
- Modify: `app/src/main/resources/application.yaml`（加 notification.base-url）
- Test: `platform-core/src/test/java/com/zxf/platform/core/infrastructure/integration/NotificationClientTest.java`
- Modify: `app/src/test/java/com/zxf/platform/AlphaOrderApiEndToEndTest.java`（@MockitoBean NotificationPort）
- Modify: `app/src/test/java/com/zxf/platform/BetaOrderApiEndToEndTest.java`（同上）

**Interfaces:**
- Produces: `NotificationPort.send(orderId, processInstanceId)`；`NotificationClient`（Resilience4j 包装）

- [ ] **Step 1: 加依赖 + 配置**

`platform-core/pom.xml`：

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-circuitbreaker</artifactId>
    <version>2.3.0</version>
</dependency>
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-retry</artifactId>
    <version>2.3.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-restclient</artifactId>
</dependency>
```

> 版本号实施时确认 Maven Central 最新稳定版。

`application.yaml` 追加：

```yaml
platform:
  notification:
    base-url: http://localhost:8081
```

- [ ] **Step 2: 写 NotificationPort 接口**

```java
package com.zxf.platform.core.domain.port;

/** 通知出站端口（文档 7.7.2 组件 11）：通知发送的领域契约。 */
public interface NotificationPort {

    void send(String orderId, String processInstanceId);
}
```

- [ ] **Step 3: 写 CorrelationIdInterceptor + RestClientConfig + ResilienceConfig**

`CorrelationIdInterceptor.java`：

```java
package com.zxf.platform.core.infrastructure.integration;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.interfaces.filter.TraceIdFilter;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * correlationId 透传拦截器（文档 7.7.2 组件 11）：下游调用注入 traceId + entity 头。
 */
public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        var traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null) {
            request.getHeaders().set(TraceIdFilter.HEADER, traceId);
        }
        var entity = EntityContext.currentOrNull();
        if (entity != null) {
            request.getHeaders().set("X-Entity", entity.name());
        }
        return execution.execute(request, body);
    }
}
```

`RestClientConfig.java`：

```java
package com.zxf.platform.core.infrastructure.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient notificationRestClient(
            @Value("${platform.notification.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(new CorrelationIdInterceptor())
                .build();
    }
}
```

`ResilienceConfig.java`：

```java
package com.zxf.platform.core.infrastructure.integration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j 手动配置（文档 7.7.2 组件 11）：核心库程序式配置，不依赖 spring-boot autoconfigure。
 *
 * <p>与流程重试协作纪律（文档）：HTTP Retry 在 CircuitBreaker 内，maxAttempts=3；
 * 流程层 failedJobRetryTimeCycle R3/PT5S 也是 3 次。两层重试总账 = 3×3=9 次（注释说明）。
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreaker notificationCircuitBreaker() {
        var config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .build();
        return CircuitBreakerRegistry.of(config).circuitBreaker("notification");
    }

    @Bean
    public Retry notificationRetry() {
        var config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .build();
        return RetryRegistry.of(config).retry("notification");
    }
}
```

- [ ] **Step 4: 写 NotificationClient**

```java
package com.zxf.platform.core.infrastructure.integration;

import com.zxf.platform.core.domain.port.NotificationPort;
import com.zxf.platform.core.infrastructure.engine.NotificationFailedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.ContextPropagator;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationClient implements NotificationPort {

    private final RestClient notificationRestClient;
    private final CircuitBreaker notificationCircuitBreaker;
    private final Retry notificationRetry;

    @Override
    public void send(String orderId, String processInstanceId) {
        var decorated = Decorators.ofRunnable(() -> doCall(orderId, processInstanceId))
                .withRetry(notificationRetry)
                .withCircuitBreaker(notificationCircuitBreaker)
                .decorate();
        try {
            decorated.run();
        } catch (Exception e) {
            throw new NotificationFailedException("通知下游失败 orderId=" + orderId + ": " + e.getMessage());
        }
    }

    private void doCall(String orderId, String processInstanceId) {
        notificationRestClient.post()
                .uri("/api/v1/notifications")
                .body(Map.of("orderId", orderId, "processInstanceId", processInstanceId))
                .retrieve()
                .toBodilessEntity();
    }
}
```

> ContextPropagator 用于线程池上下文传播，此处同步调用暂不需要。

- [ ] **Step 5: 增强 SendNotificationDelegate**

```java
@Slf4j
@Component("sendNotificationDelegate")
public class SendNotificationDelegate extends EntityContextAwareDelegate {

    private final NotificationPort notificationClient;
    private final AuditPort audit;

    public SendNotificationDelegate(MeterRegistry meterRegistry,
                                    NotificationPort notificationClient,
                                    AuditPort audit) {
        super(meterRegistry);
        this.notificationClient = notificationClient;
        this.audit = audit;
    }

    @Override
    protected void doExecute(DelegateExecution execution) {
        var orderId = (String) execution.getVariable("orderId");
        notificationClient.send(orderId, execution.getProcessInstanceId());
        audit.record("APPROVAL_NOTIFICATION", "orderId=" + orderId);
        log.info("发送审批完成通知 orderId={} processInstanceId={}", orderId, execution.getProcessInstanceId());
    }
}
```

> 移除了原 "888" 前缀检查——失败源统一为 NotificationClient 下游调用。

- [ ] **Step 6: 写 NotificationClientTest**

```java
package com.zxf.platform.core.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zxf.platform.core.infrastructure.engine.NotificationFailedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class NotificationClientTest {

    @Test
    void 下游成功时正常调用() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());

        var client = newClient(builder.build());
        client.send("order-1", "pi-1");
        server.verify();
    }

    @Test
    void 下游持续失败时抛NotificationFailedException() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());

        var client = newClient(builder.build());
        assertThatThrownBy(() -> client.send("order-1", "pi-1"))
                .isInstanceOf(NotificationFailedException.class);
    }

    private NotificationClient newClient(RestClient restClient) {
        var cb = CircuitBreaker.of("notification", CircuitBreakerConfig.custom()
                .failureRateThreshold(50).slidingWindowSize(4).build());
        var retry = Retry.of("notification", RetryConfig.custom()
                .maxAttempts(3).waitDuration(Duration.ofMillis(10)).build());
        return new NotificationClient(restClient, cb, retry);
    }
}
```

- [ ] **Step 7: e2e 加 @MockitoBean NotificationPort**

`AlphaOrderApiEndToEndTest` + `BetaOrderApiEndToEndTest`：

```java
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@MockitoBean
private NotificationPort notificationPort;
```

正常路径测试方法无需额外 stub（Mockito 默认 void 方法 doNothing）。

死信测试方法（如有）stub：
```java
// 在死信测试方法内
doThrow(new NotificationFailedException("mock 下游不可达"))
        .when(notificationPort).send(any(), any());
```

- [ ] **Step 8: 运行验证 + 提交**

```bash
mvn -B test -pl platform-core -Dtest=NotificationClientTest
mvn -B verify -Palpha
mvn -B verify -Pbeta
git add -A
git commit -m "feat(flowable): 7.7 组件 11 Resilience4j 容错 + correlationId 拦截器

- NotificationPort 端口 + NotificationClient（RestClient + CircuitBreaker + Retry）
- CorrelationIdInterceptor 注入 X-Trace-Id + X-Entity 头
- ResilienceConfig 程序式配置（核心库，SB4 无关）
- SendNotificationDelegate 增强为调下游通知服务
- e2e @MockitoBean NotificationPort（正常 doNothing / 死信 doThrow）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 批次 4：starter 骨架（组件 14）

### Task 7: 组件 14 — platform-flowable starter

**Files:**
- Create: `platform-flowable-autoconfigure/pom.xml`
- Create: `platform-flowable-autoconfigure/src/main/java/com/zxf/platform/flowable/autoconfigure/FlowableHealthAutoConfiguration.java`
- Create: `platform-flowable-autoconfigure/src/main/java/com/zxf/platform/flowable/autoconfigure/FlowableHealthProperties.java`
- Create: `platform-flowable-autoconfigure/src/main/java/com/zxf/platform/flowable/autoconfigure/FlowableEngineHealthIndicator.java`
- Create: `platform-flowable-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `platform-flowable-autoconfigure/src/test/java/com/zxf/platform/flowable/autoconfigure/FlowableHealthAutoConfigurationTest.java`
- Create: `platform-flowable-starter/pom.xml`
- Modify: `pom.xml`（root：modules + dependencyManagement）
- Modify: `app/pom.xml`（加 starter 依赖）

**Interfaces:**
- Produces: `FlowableHealthAutoConfiguration`（@AutoConfiguration）；`FlowableEngineHealthIndicator`

- [ ] **Step 1: root pom 加模块**

`pom.xml` `<modules>` 追加：

```xml
<module>platform-flowable-autoconfigure</module>
<module>platform-flowable-starter</module>
```

`<dependencyManagement>` 追加：

```xml
<dependency>
    <groupId>com.zxf.platform</groupId>
    <artifactId>platform-flowable-autoconfigure</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>com.zxf.platform</groupId>
    <artifactId>platform-flowable-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 2: 写 platform-flowable-autoconfigure/pom.xml**

```xml
<project>
    <parent>
        <groupId>com.zxf.platform</groupId>
        <artifactId>multi-entity-platform</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>platform-flowable-autoconfigure</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flowable</groupId>
            <artifactId>flowable-engine</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

> flowable-engine 和 actulator 用 provided（starter 不强依赖，由引入方提供）。

- [ ] **Step 3: 写 AutoConfiguration + Properties + HealthIndicator**

`FlowableHealthProperties.java`：

```java
package com.zxf.platform.flowable.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "platform.flowable.health")
public record FlowableHealthProperties(@DefaultValue("true") boolean enabled) {
}
```

`FlowableEngineHealthIndicator.java`：

```java
package com.zxf.platform.flowable.autoconfigure;

import org.flowable.engine.RuntimeService;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

public class FlowableEngineHealthIndicator extends AbstractHealthIndicator {

    private final RuntimeService runtimeService;

    public FlowableEngineHealthIndicator(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        var count = runtimeService.createProcessInstanceQuery().count();
        builder.up().withDetail("activeProcessInstances", count);
    }
}
```

`FlowableHealthAutoConfiguration.java`：

```java
package com.zxf.platform.flowable.autoconfigure;

import org.flowable.engine.RuntimeService;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(RuntimeService.class)
@ConditionalOnProperty(prefix = "platform.flowable.health", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FlowableHealthProperties.class)
public class FlowableHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "flowableHealthIndicator")
    public HealthIndicator flowableHealthIndicator(RuntimeService runtimeService) {
        return new FlowableEngineHealthIndicator(runtimeService);
    }
}
```

`AutoConfiguration.imports`：

```
com.zxf.platform.flowable.autoconfigure.FlowableHealthAutoConfiguration
```

- [ ] **Step 4: 写 ApplicationContextRunner 测试**

```java
package com.zxf.platform.flowable.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FlowableHealthAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlowableHealthAutoConfiguration.class));

    @Test
    void 有RuntimeService且默认启用时注册健康指标() {
        runner.withBean(RuntimeService.class, () -> org.mockito.Mockito.mock(RuntimeService.class))
                .run(context -> assertThat(context).hasSingleBean(HealthIndicator.class));
    }

    @Test
    void 无RuntimeService时不注册() {
        runner.withClassLoader(new FilteredClassLoader(RuntimeService.class))
                .run(context -> assertThat(context).doesNotHaveBean(HealthIndicator.class));
    }

    @Test
    void 显式禁用时不注册() {
        runner.withBean(RuntimeService.class, () -> org.mockito.Mockito.mock(RuntimeService.class))
                .withPropertyValues("platform.flowable.health.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HealthIndicator.class));
    }
}
```

- [ ] **Step 5: 写 platform-flowable-starter/pom.xml**

```xml
<project>
    <parent>
        <groupId>com.zxf.platform</groupId>
        <artifactId>multi-entity-platform</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>platform-flowable-starter</artifactId>
    <description>纯依赖聚合：引入即获得 Flowable 公共组件自动配置</description>

    <dependencies>
        <dependency>
            <groupId>com.zxf.platform</groupId>
            <artifactId>platform-flowable-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flowable</groupId>
            <artifactId>flowable-spring-boot-starter-process</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 6: app pom 加 starter 依赖**

```xml
<dependency>
    <groupId>com.zxf.platform</groupId>
    <artifactId>platform-flowable-starter</artifactId>
</dependency>
```

- [ ] **Step 7: e2e 验证 health 端点**

`AlphaOrderApiEndToEndTest` 追加：

```java
@Test
void actuatorHealth包含Flowable健康检查() throws Exception {
    mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.components.flowable").exists());
}
```

- [ ] **Step 8: 运行验证 + 提交**

```bash
mvn -B verify -Palpha
mvn -B verify -Pbeta
git add -A
git commit -m "feat(flowable): 7.7 组件 14 platform-flowable starter 骨架

- platform-flowable-autoconfigure：@AutoConfiguration + 条件组合 + imports 注册
- FlowableEngineHealthIndicator：/actuator/health 输出 flowable 组件
- platform-flowable-starter：纯依赖聚合空 jar
- ApplicationContextRunner 测试覆盖条件分支
- app 依赖 starter，health 端点自动包含 flowable

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 完成标准

全部 7 个 Task 完成后：

- [ ] `mvn -B verify -Palpha` 全绿
- [ ] `mvn -B verify -Pbeta` 全绿
- [ ] 14 个组件全部落地（8 个已落地 + 6 个新增 + 2 个补全）
- [ ] 设计文档 7.7 落地状态更新（可选）
