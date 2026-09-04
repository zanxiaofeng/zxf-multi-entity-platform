package com.zxf.platform.core.infrastructure.engine;

import com.zxf.platform.core.domain.event.ApprovalNotifiedEvent;
import com.zxf.platform.core.domain.model.NotificationFailedException;
import com.zxf.platform.core.domain.model.OrderId;
import com.zxf.platform.core.domain.port.NotificationPort;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 通用任务：审批链结束后发送通知——两个实体的流程定义共用（文档 7.2 任务实现分流：
 * 通用任务进 core，实体专属任务进实体模块）。
 *
 * <p>两个实体的 BPMN 中本任务均为 <b>async 节点</b>（通知类任务的典型形态）：由引擎 Job
 * 执行器线程运行，请求上下文不在——实体上下文由基类从流程变量重建（文档 7.3③ 双保险闭环）。
 *
 * <p><b>组件 11 升级</b>（文档 7.7.2）：失败源由 demo 占位逻辑（"888" 前缀判断）改为
 * 真实下游调用——{@link NotificationPort} 由 {@code NotificationClient} 实现，
 * 经 RestClient + Resilience4j（CircuitBreaker + Retry）包装。下游失败抛
 * {@link NotificationFailedException} 仍走 Flowable Job 重试→死信路径（组件 4 闭环）。
 *
 * <p>审计走领域事件（评审修复 P3）：本任务运行在引擎 Job 事务内，同步调用审计会在
 * Job 事务回滚时留下幻影审计条目——改为发布 {@link ApprovalNotifiedEvent}（事务内仅
 * 注册意图），由 {@code AuditService} 在 AFTER_COMMIT 消费（文档 8.1 规则 11），
 * 与 {@code OrderCreatedEvent} 的审计路径同构。
 *
 * <p>delegate 纪律（文档 8.1 规则 10）：单例无状态——禁止在字段中保存任何执行态
 * （execution、请求/响应对象），多 Job 线程并发调用同一 Spring 单例。
 */
@Slf4j
@Component("sendNotificationDelegate")
public class SendNotificationDelegate extends EntityContextAwareDelegate {

    private final NotificationPort notificationPort;
    private final ApplicationEventPublisher events;

    public SendNotificationDelegate(MeterRegistry meterRegistry,
                                    NotificationPort notificationPort,
                                    ApplicationEventPublisher events) {
        super(meterRegistry);
        this.notificationPort = notificationPort;
        this.events = events;
    }

    @Override
    protected void doExecute(DelegateExecution execution) {
        var orderId = (String) execution.getVariable("orderId");
        Assert.hasText(orderId, "流程变量 orderId 缺失");
        // 失败源 = NotificationClient 下游调用：技术异常（连接失败 / 5xx 等）经 Resilience4j
        // Retry 耗尽后抛 NotificationFailedException，配合 failedJobRetryTimeCycle（R3/PT5S）
        // 走 Job 重试→死信路径（文档 7.7.1 组件 4）；BpmnError 走 BPMN 边界事件分支不重试（组件 5）
        notificationPort.send(orderId, execution.getProcessInstanceId());
        // 审计 AFTER_COMMIT 化：Job 事务回滚不留幻影审计（见类注释）
        events.publishEvent(new ApprovalNotifiedEvent(OrderId.of(Long.parseLong(orderId)),
                execution.getProcessInstanceId()));
        log.info("发送审批完成通知 orderId={} processInstanceId={}",
                orderId, execution.getProcessInstanceId());
    }
}
