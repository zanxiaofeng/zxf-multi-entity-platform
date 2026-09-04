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
 * <p>{@link TaskAssignmentRule} 同样经 {@code ObjectProvider} 解析（java-coding-standard §3.3：
 * Optional 禁止用作字段/构造器参数——可选依赖的惯用表达统一为 ObjectProvider，与
 * {@code taskServiceProvider} 同款）：实现缺失或多实现（无法唯一定位）记 WARN 后跳过——
 * 两实体装配均提供实现，缺失属装配漂移应当可见（评审修复 P3，与此前静默跳过对照）；
 * 分配失败本身不回滚业务（{@code isFailOnException=false}）。
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
        var rules = assignmentRule.orderedStream().toList();
        if (rules.isEmpty()) {
            // 评审修复 P3：两实体装配均提供 TaskAssignmentRule 实现——缺失属装配漂移，
            // WARN 暴露而非静默跳过（否则候选人分配悄然失效，与注释承诺的容错语义不符）
            log.warn("当前装配未提供 TaskAssignmentRule 实现，跳过候选人分配（装配漂移？检查实体模块是否进入 classpath）");
            return;
        }
        if (rules.size() > 1) {
            // 评审修复 P3：getIfAvailable 在多 bean 时抛 NoUniqueBeanDefinitionException 且被
            // isFailOnException=false 吞掉——显式发现并列出，避免静默误选或吞异常
            log.warn("装配了多个 TaskAssignmentRule 实现（{}），无法确定唯一策略，跳过候选人分配",
                    rules.stream().map(rule -> rule.getClass().getName()).toList());
            return;
        }
        if (!(event instanceof FlowableEngineEntityEvent entityEvent)) {
            return;
        }
        if (!(entityEvent.getEntity() instanceof Task task)) {
            return;
        }
        var candidates = rules.getFirst().candidatesFor(task.getTaskDefinitionKey());
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
