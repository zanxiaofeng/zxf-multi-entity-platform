package com.zxf.platform.alpha.adapter;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.ForEntity;
import com.zxf.platform.core.infrastructure.engine.EntityContextAwareDelegate;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

/**
 * Alpha 专属风控任务（文档 7.2 落地要点 3 + 7.7.1 组件 5 错误分类示范）：BPMN 中以
 * 委托表达式 {@code ${alphaRiskCheckDelegate}} 引用。
 *
 * <p>纪律同计价策略：{@code @ForEntity(EntityType.ALPHA)} 限定（装配冒烟守护 delegate 全装配，文档 7.4）；
 * delegate 单例无状态（文档 8.1 规则 10，ArchUnit 守护）。
 *
 * <p><b>错误分类示范（7.7.1 组件 5）</b>：业务错误抛 {@link BpmnError}（errorCode=
 * {@code RISK_REJECTED}），由 BPMN 边界错误事件捕获走"风控拒绝"分支——<b>不触发 Job 重试</b>
 * （与 技术异常配合 {@code failedJobRetryTimeCycle} 的重试路径对照）。demo 触发条件：
 * orderId 以 "999" 开头（示范用，生产按真实风控规则）；正常订单（自增 id）不触发。
 *
 * <p>本任务为同步节点，运行在发起方请求线程，上下文本就在（基类直接放行）；
 * 若改为 async 节点，基类会从流程变量重建实体上下文（文档 7.3③）。
 */
@Slf4j
@Component("alphaRiskCheckDelegate")
@ForEntity(EntityType.ALPHA)
public class AlphaRiskCheckDelegate extends EntityContextAwareDelegate {

    /** BpmnError errorCode——与 BPMN boundaryEvent errorRef 匹配。 */
    public static final String RISK_REJECTED_ERROR = "RISK_REJECTED";

    public AlphaRiskCheckDelegate(MeterRegistry meterRegistry) {
        super(meterRegistry);
    }

    @Override
    protected void doExecute(DelegateExecution execution) {
        var orderId = (String) execution.getVariable("orderId");
        log.info("Alpha 风控检查 orderId={}", orderId);

        // 示范 BpmnError 错误分类（文档 7.7.1 组件 5）：业务错误走 BPMN 分支，不触发 Job 重试
        if (orderId != null && orderId.startsWith("999")) {
            throw new BpmnError(RISK_REJECTED_ERROR, "风控拒绝：orderId=" + orderId + " 命中黑名单");
        }
    }
}
