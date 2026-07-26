package com.zxf.platform.alpha.adapter;

import com.zxf.platform.core.infrastructure.engine.EntityContextAwareDelegate;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Alpha 专属风控任务（文档 7.2 落地要点 3）：BPMN 中以委托表达式
 * {@code ${alphaRiskCheckDelegate}} 引用。
 *
 * <p>纪律同计价策略：{@code @Profile("alpha")} 限定（装配冒烟守护 delegate 全装配，文档 7.4）；
 * delegate 单例无状态（文档 8.1 规则 10，ArchUnit 守护）。
 *
 * <p>本任务为同步节点，运行在发起方请求线程，上下文本就在（基类直接放行）；
 * 若改为 async 节点，基类会从流程变量重建实体上下文（文档 7.3③）。
 */
@Slf4j
@Component("alphaRiskCheckDelegate")
@Profile("alpha")
public class AlphaRiskCheckDelegate extends EntityContextAwareDelegate {

    @Override
    protected void doExecute(DelegateExecution execution) {
        log.info("Alpha 风控检查 orderId={}", execution.getVariable("orderId"));
    }
}
