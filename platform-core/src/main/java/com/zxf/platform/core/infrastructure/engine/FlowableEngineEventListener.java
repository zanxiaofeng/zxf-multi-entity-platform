package com.zxf.platform.core.infrastructure.engine;

import com.zxf.platform.core.context.EntityContext;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
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
 * async 事件（{@code JOB_EXECUTION_FAILURE} 等）经 {@code EntityContextPropagatingTaskExecutor}
 * 传播——{@link EntityContext#currentOrNull()} 安全获取，取不到标 {@code null}。
 */
@Component
@RequiredArgsConstructor
public class FlowableEngineEventListener implements FlowableEventListener {

    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher eventPublisher;

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
        var entity = EntityContext.currentOrNull();

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
