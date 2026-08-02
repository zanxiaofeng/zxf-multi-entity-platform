package com.zxf.platform.alpha.adapter;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.ForEntity;
import com.zxf.platform.core.domain.model.OrderId;
import com.zxf.platform.core.domain.port.OrderRepository;
import com.zxf.platform.core.infrastructure.engine.EntityContextAwareDelegate;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

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
 * 商品名以 {@value #RISK_ITEM_PREFIX} 开头（请求可控，e2e 可真实触发）；生产按真实风控规则替换。
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

    /** 风控拒绝触发前缀（demo 示范）：商品名以此前缀开头即判定命中黑名单。 */
    public static final String RISK_ITEM_PREFIX = "risk-";

    private final OrderRepository orderRepository;

    public AlphaRiskCheckDelegate(MeterRegistry meterRegistry, OrderRepository orderRepository) {
        super(meterRegistry);
        this.orderRepository = orderRepository;
    }

    @Override
    protected void doExecute(DelegateExecution execution) {
        var orderId = (String) execution.getVariable("orderId");
        Assert.hasText(orderId, "流程变量 orderId 缺失");
        log.info("Alpha 风控检查 orderId={}", orderId);

        // 示范 BpmnError 错误分类（文档 7.7.1 组件 5）：业务错误走 BPMN 分支，不触发 Job 重试。
        // 触发条件挂在订单数据上（item 前缀），替代不可达的自增 id 前缀判断——
        // 同步节点与下单同事务，订单行对当前事务可见
        var order = orderRepository.findById(new OrderId(orderId))
                .orElseThrow(() -> new IllegalStateException("订单不存在 orderId=" + orderId));
        if (order.item().startsWith(RISK_ITEM_PREFIX)) {
            throw new BpmnError(RISK_REJECTED_ERROR,
                    "风控拒绝：orderId=" + orderId + " item=" + order.item() + " 命中黑名单");
        }
    }
}
