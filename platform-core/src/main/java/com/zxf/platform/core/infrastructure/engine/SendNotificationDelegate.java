package com.zxf.platform.core.infrastructure.engine;

import com.zxf.platform.core.domain.port.AuditPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/**
 * 通用任务：审批链结束后发送通知——两个实体的流程定义共用（文档 7.2 任务实现分流：
 * 通用任务进 core，实体专属任务进实体模块）。
 *
 * <p>两个实体的 BPMN 中本任务均为 <b>async 节点</b>（通知类任务的典型形态）：由引擎 Job
 * 执行器线程运行，请求上下文不在——实体上下文由基类从流程变量重建（文档 7.3③ 双保险闭环）。
 * 写审计轨迹是 demo 的可观测出口：审计条目的实体维度即来自重建的上下文，端到端测试据此断言。
 *
 * <p>delegate 纪律（文档 8.1 规则 10）：单例无状态——禁止在字段中保存任何执行态
 * （execution、请求/响应对象），多 Job 线程并发调用同一 Spring 单例。
 *
 * <p>依赖领域端口 {@link AuditPort} 而非 observation 实现：引擎适配器不得直达
 * 基础设施内部（文档 5.1.1 onion 规则，ArchUnit 守护）。
 */
@Slf4j
@Component("sendNotificationDelegate")
@RequiredArgsConstructor
public class SendNotificationDelegate extends EntityContextAwareDelegate {

    private final AuditPort audit;

    @Override
    protected void doExecute(DelegateExecution execution) {
        log.info("发送审批完成通知 orderId={} processInstanceId={}",
                execution.getVariable("orderId"), execution.getProcessInstanceId());
        audit.record("APPROVAL_NOTIFICATION", "orderId=" + execution.getVariable("orderId"));
    }
}
