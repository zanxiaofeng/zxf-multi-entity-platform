package com.zxf.platform.beta;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Beta 专属审计留痕任务（文档 7.2 落地要点 3）：审批链结束后写入 Beta 专属审计扩展
 * （对应本模块迁移脚本的 {@code beta_audit_extra} 表）。BPMN 中以
 * {@code ${betaAuditExtraDelegate}} 引用。
 *
 * <p>纪律同 Alpha delegate：{@code @Profile("beta")} 限定；单例无状态（文档规则 10）。
 */
@Component("betaAuditExtraDelegate")
@Profile("beta")
public class BetaAuditExtraDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(BetaAuditExtraDelegate.class);

    @Override
    public void execute(DelegateExecution execution) {
        // demo 只打日志；生产实现写 beta_audit_extra 表（在本部署数据源内，随引擎事务提交）
        log.info("Beta 审计留痕 orderId={}", execution.getVariable("orderId"));
    }
}
