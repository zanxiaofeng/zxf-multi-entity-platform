package com.zxf.platform.core.infrastructure.engine;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.RuntimeService;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Flowable 全局事件监听器（文档 7.7.1 组件 1 + 7.7.1 组件 2）。
 *
 * <p><b>组件 1（流程指标）</b>：所有引擎事件对接 Micrometer——{@code Counter} 按事件类型 +
 * processDefinitionKey + entity 三维打标，接入监控后可按实体/流程/事件类型分别告警。
 * 与 {@link com.zxf.platform.core.infrastructure.observation.MetricsConfig} 的 entity common tag 互补
 * （后者给所有 meter 打 entity 标，本监听器在此基础上加流程维度）。
 *
 * <p><b>组件 2（事件桥接）</b>：引擎事件包装为 {@link FlowableProcessEvent} 转发到 Spring
 * {@link ApplicationEventPublisher}——业务方用 {@code @EventListener} 订阅，不依赖 Flowable API。
 *
 * <p><b>关键纪律</b>：{@link #isFailOnException()} 返回 {@code false}——审计/监控失败
 * 不得回滚业务事务（文档 7.7.1 组件 1 原文）。监听器是 Spring 单例，被多线程并发调用——
 * {@code MeterRegistry.counter()} 与 {@code ApplicationEventPublisher.publishEvent()} 均线程安全。
 *
 * <p>entity 维度：同步事件（{@code PROCESS_STARTED} 等）在请求线程触发，{@link EntityContext} 在；
 * async 事件（{@code JOB_EXECUTION_FAILURE} 等）在 delegate 重建窗口外触发，线程上下文恒空——
 * 从 execution 的 {@code entity} 流程变量兜底取标（评审修复 M4），取不到标 {@code null}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowableEngineEventListener implements FlowableEventListener {

    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;
    /** Job 级事件兜底取标用（延迟解析防循环，见 {@link #resolveEntityName}）；引擎未就绪时跳过。 */
    private final ObjectProvider<RuntimeService> runtimeServiceProvider;

    @Override
    public void onEvent(FlowableEvent event) {
        // FlowableEvent 基础接口只有 getType()；流程相关信息在子接口 FlowableEngineEvent 上
        String processDefinitionId = null;
        String processInstanceId = null;
        if (event instanceof FlowableEngineEvent engineEvent) {
            processDefinitionId = engineEvent.getProcessDefinitionId();
            processInstanceId = engineEvent.getProcessInstanceId();
        }
        var processKey = extractProcessKey(processDefinitionId);
        var entity = currentEntity(event);

        // 组件 1：流程维度指标（Counter 按事件类型 + processDefinitionKey + entity）
        meterRegistry.counter("flowable.engine.events",
                        "type", event.getType().name(),
                        "processDefinitionKey", processKey != null ? processKey : "unknown",
                        "entity", entity != null ? entity.name() : "none")
                .increment();

        // 组件 2：桥接到 Spring Event（业务方用 @EventListener 订阅，不依赖 Flowable API）
        eventPublisher.publishEvent(new FlowableProcessEvent(
                event.getType().name(),
                processDefinitionId,
                processInstanceId,
                entity));
    }

    /**
     * 解析当前实体：线程上下文优先；async Job 事件（{@code JOB_EXECUTION_FAILURE} 等）
     * 经 {@link RuntimeService} 从流程变量兜底（评审修复 M4）。
     *
     * <p><b>为什么需要兜底</b>：Job 由 acquisition 线程从 {@code ACT_RU_JOB} 拉取后提交，
     * 提交时无请求上下文（装饰空转）；delegate 基类的重建窗口只覆盖 delegate 执行段——
     * 引擎在窗口外触发的 Job 级事件（成败/死信，恰是故障排查最需要的维度）ThreadLocal
     * 恒空，指标将永久打 {@code entity=none}。
     *
     * <p><b>实现约束</b>：Flowable 8 公共事件 API 不携带 {@code DelegateExecution}
     * （只有 {@code getExecutionId()} 字符串），兜底经 {@code RuntimeService.getVariable}
     * 读取——与 delegate 基类同一流程变量契约（{@value EntityContextAwareDelegate#ENTITY_VARIABLE}）。
     * {@code RuntimeService} 经 {@link ObjectProvider} 延迟解析（监听器须在引擎构建前注册，
     * 直接注入形成循环，与 {@code TaskAssignmentListener} 同款解法）。查询仅发生在
     * "线程无上下文且事件带 executionId"的场景（即 Job/Timer 级事件），其余零成本；
     * 查询失败（如 SUCCESS 后 execution 已推进/删除）静默降级 {@code none}——
     * 观测兜底不得影响业务（{@link #isFailOnException()} 纪律）。
     */
    private @Nullable EntityType currentEntity(FlowableEvent event) {
        var fromThread = EntityContext.currentOrNull();
        if (fromThread != null) {
            return fromThread;
        }
        var entityName = resolveEntityName(event);
        return entityName != null ? EntityType.valueOf(entityName) : null;
    }

    /** Job 级事件的流程变量兜底；非引擎事件 / 无 executionId / 引擎未就绪均返回 {@code null}。 */
    private @Nullable String resolveEntityName(FlowableEvent event) {
        if (!(event instanceof FlowableEngineEvent engineEvent) || engineEvent.getExecutionId() == null) {
            return null;
        }
        var runtimeService = runtimeServiceProvider.getIfAvailable();
        if (runtimeService == null) {
            return null;
        }
        try {
            if (runtimeService.getVariable(engineEvent.getExecutionId(),
                    EntityContextAwareDelegate.ENTITY_VARIABLE) instanceof String entityName) {
                return entityName;
            }
            return null;
        } catch (RuntimeException ex) {
            // 观测兜底降级（exception-handling §5.2：可忽略的次要异常记 debug）——
            // 如 SUCCESS 事件触发时 execution 已推进/删除，entity 标退回 none
            log.debug("Job 事件 entity 兜底查询失败，降级 none executionId={}",
                    engineEvent.getExecutionId(), ex);
            return null;
        }
    }

    @Override
    public boolean isFailOnException() {
        // 审计/监控失败不得回滚业务事务（文档 7.7.1 组件 1 关键纪律）
        return false;
    }

    @Override
    public boolean isFireOnTransactionLifecycleEvent() {
        // 不在事务生命周期事件上触发（Outbox 场景才需要，组件 12 推迟）
        return false;
    }

    @Override
    public String getOnTransaction() {
        return null;
    }

    /** 从 processDefinitionId 提取 key（格式 {@code order-approval:1:12345} → {@code order-approval}）。 */
    private String extractProcessKey(String processDefinitionId) {
        if (processDefinitionId == null) {
            return null;
        }
        var colon = processDefinitionId.indexOf(':');
        return colon > 0 ? processDefinitionId.substring(0, colon) : processDefinitionId;
    }
}
