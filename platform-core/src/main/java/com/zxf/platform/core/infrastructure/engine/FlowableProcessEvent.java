package com.zxf.platform.core.infrastructure.engine;

import com.zxf.platform.core.context.EntityType;

/**
 * Flowable 引擎事件 → Spring Event 桥接（文档 7.7.1 组件 2）。
 *
 * <p>由 {@link FlowableEngineEventListener} 在引擎事件触发时发布，业务方用熟悉的
 * {@code @EventListener} / {@code @TransactionalEventListener(AFTER_COMMIT)} 订阅，
 * 不依赖 Flowable API。事务纪律同 7.5.3（有副作用的监听器走 AFTER_COMMIT）。
 *
 * @param eventType 引擎事件类型名（{@code FlowableEngineEventType.name()}，如 PROCESS_STARTED）
 * @param processDefinitionId 流程定义 ID（含版本，如 {@code order-approval:1:12345}）
 * @param processInstanceId 流程实例 ID（引擎生成的 UUID，经 IdGenerator）
 * @param entity 触发时的实体上下文（同步事件在请求线程，entity 在；
 *               async 事件经 {@code EntityContextPropagatingTaskExecutor} 传播；取不到为 null）
 */
public record FlowableProcessEvent(
        String eventType,
        String processDefinitionId,
        String processInstanceId,
        EntityType entity) {
}
