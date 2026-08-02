package com.zxf.platform.core.infrastructure.engine;

import com.zxf.platform.core.domain.model.NotificationFailedException;
import com.zxf.platform.core.domain.port.AuditPort;
import com.zxf.platform.core.domain.port.NotificationPort;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 通用任务：审批链结束后发送通知——两个实体的流程定义共用（文档 7.2 任务实现分流：
 * 通用任务进 core，实体专属任务进实体模块）。
 *
 * <p>两个实体的 BPMN 中本任务均为 <b>async 节点</b>（通知类任务的典型形态）：由引擎 Job
 * 执行器线程运行，请求上下文不在——实体上下文由基类从流程变量重建（文档 7.3③ 双保险闭环）。
 * 写审计轨迹是 demo 的可观测出口：审计条目的实体维度即来自重建的上下文，端到端测试据此断言。
 *
 * <p><b>组件 11 升级</b>（文档 7.7.2）：失败源由 demo 占位逻辑（"888" 前缀判断）改为
 * 真实下游调用——{@link NotificationPort} 由 {@code NotificationClient} 实现，
 * 经 RestClient + Resilience4j（CircuitBreaker + Retry）包装。下游失败抛
 * {@link NotificationFailedException} 仍走 Flowable Job 重试→死信路径（组件 4 闭环）。
 *
 * <p>delegate 纪律（文档 8.1 规则 10）：单例无状态——禁止在字段中保存任何执行态
 * （execution、请求/响应对象），多 Job 线程并发调用同一 Spring 单例。
 *
 * <p>依赖领域端口 {@link NotificationPort} / {@link AuditPort} 而非具体实现：
 * 引擎适配器不得直达基础设施内部（文档 5.1.1 onion 规则，ArchUnit 守护）。
 */
@Slf4j
@Component("sendNotificationDelegate")
public class SendNotificationDelegate extends EntityContextAwareDelegate {

    private final NotificationPort notificationPort;
    private final AuditPort audit;

    public SendNotificationDelegate(MeterRegistry meterRegistry,
                                    NotificationPort notificationPort,
                                    AuditPort audit) {
        super(meterRegistry);
        this.notificationPort = notificationPort;
        this.audit = audit;
    }

    @Override
    protected void doExecute(DelegateExecution execution) {
        var orderId = (String) execution.getVariable("orderId");
        Assert.hasText(orderId, "流程变量 orderId 缺失");
        // 失败源 = NotificationClient 下游调用：技术异常（连接失败 / 5xx 等）经 Resilience4j
        // Retry 耗尽后抛 NotificationFailedException，配合 failedJobRetryTimeCycle（R3/PT5S）
        // 走 Job 重试→死信路径（文档 7.7.1 组件 4）；BpmnError 走 BPMN 边界事件分支不重试（组件 5）
        notificationPort.send(orderId, execution.getProcessInstanceId());
        audit.record("APPROVAL_NOTIFICATION", "orderId=" + orderId);
        log.info("发送审批完成通知 orderId={} processInstanceId={}",
                orderId, execution.getProcessInstanceId());
    }
}
