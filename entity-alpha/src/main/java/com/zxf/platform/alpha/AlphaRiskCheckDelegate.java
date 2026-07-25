package com.zxf.platform.alpha;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Alpha 专属风控任务（文档 7.2 落地要点 3）：BPMN 中以委托表达式
 * {@code ${alphaRiskCheckDelegate}} 引用。
 *
 * <p>纪律同计价策略：{@code @Profile("alpha")} 限定（装配冒烟守护 delegate 全装配，文档 7.4）；
 * delegate 单例无状态（文档规则 10，ArchUnit 守护）。
 */
@Component("alphaRiskCheckDelegate")
@Profile("alpha")
public class AlphaRiskCheckDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(AlphaRiskCheckDelegate.class);

    @Override
    public void execute(DelegateExecution execution) {
        log.info("Alpha 风控检查 orderId={}", execution.getVariable("orderId"));
    }
}
