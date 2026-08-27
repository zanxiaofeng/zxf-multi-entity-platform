package com.zxf.platform.core.infrastructure.engine;

import com.zxf.platform.core.domain.port.TaskAssignmentRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 候选人分配监听器（文档 7.7.1 组件 6）：TASK_CREATED 时按策略分配候选人。
 *
 * <p>替代 ActivityBehaviorFactory（文档建议）——示范级用全局事件监听器更简洁，
 * 复用组件 1 的监听器注册机制。分配失败不回滚业务（isFailOnException=false）。
 *
 * <p>{@link TaskService} 经 {@link ObjectProvider} 延迟解析：监听器 bean 需在
 * {@code processEngine} 之前构造（注册到 {@code setEventListeners}），而 {@code taskServiceBean}
 * 又依赖 {@code processEngine}——直接构造注入形成循环。{@code ObjectProvider} 把解析推迟到
 * 第一次事件触发时（此时 {@code processEngine} 与 {@code taskServiceBean} 均已就绪）。
 *
 * <p>{@link TaskAssignmentRule} 同样经 {@link ObjectProvider} 解析（java-coding-standard §3.3：
 * Optional 禁止用作字段/构造器参数——可选依赖的惯用表达统一为 ObjectProvider，与
 * {@code taskServiceProvider} 同款）：当前装配实体未提供该扩展点实现时
 * {@code getIfAvailable()} 返回 {@code null}，静默跳过分配（与 {@code isFailOnException=false}
 * 的容错语义一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskAssignmentListener implements FlowableEventListener {

    private final ObjectProvider<TaskService> taskServiceProvider;
    private final ObjectProvider<TaskAssignmentRule> assignmentRule;

    @Override
    public void onEvent(FlowableEvent event) {
        if (event.getType() != FlowableEngineEventType.TASK_CREATED) {
            return;
        }
        var rule = assignmentRule.getIfAvailable();
        if (rule == null) {
            return;
        }
        if (!(event instanceof FlowableEngineEntityEvent entityEvent)) {
            return;
        }
        if (!(entityEvent.getEntity() instanceof Task task)) {
            return;
        }
        var candidates = rule.candidatesFor(task.getTaskDefinitionKey());
        if (candidates.isEmpty()) {
            return;
        }
        var taskService = taskServiceProvider.getObject();
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
