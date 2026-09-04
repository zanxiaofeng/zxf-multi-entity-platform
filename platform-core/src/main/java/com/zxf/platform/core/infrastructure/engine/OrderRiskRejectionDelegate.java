package com.zxf.platform.core.infrastructure.engine;

import com.zxf.platform.core.domain.model.OrderId;
import com.zxf.platform.core.domain.port.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 通用任务：风控拒绝分支的订单状态落账（评审修复 M3 方案 b）。
 *
 * <p>Alpha BPMN 的错误边界事件捕获 {@code BpmnError(RISK_REJECTED)} 后，拒绝分支挂本任务
 * ——订单行保留（供审计与追溯，不回滚），状态经 {@code Order.markRiskRejected()} 转移为
 * RISK_REJECTED；下游 Outbox 事件由 {@code OrderApplicationService} 按 status 分流为
 * ORDER_REJECTED（拒绝订单不再以"已创建"语义广播）。
 *
 * <p>同步节点（与下单同事务、同请求线程），上下文本就在（基类直接放行）；订单经
 * {@code orderId} 从流程变量重新加载（一级缓存命中同一 managed 实例），状态修改由
 * Hibernate 脏检查随事务写回。Beta 流程无风控节点，不引用本任务——但任务本身操作
 * 共享内核领域模型、无实体差异逻辑，归 core 通用任务（与 SendNotificationDelegate
 * 同判据：core 通用任务不要求两实体都用）。
 *
 * <p>delegate 纪律（文档 8.1 规则 10）：单例无状态。
 */
@Slf4j
@Component("orderRiskRejectionDelegate")
public class OrderRiskRejectionDelegate extends EntityContextAwareDelegate {

    private final OrderRepository orderRepository;

    public OrderRiskRejectionDelegate(MeterRegistry meterRegistry, OrderRepository orderRepository) {
        super(meterRegistry);
        this.orderRepository = orderRepository;
    }

    @Override
    protected void doExecute(DelegateExecution execution) {
        var orderId = (String) execution.getVariable("orderId");
        Assert.hasText(orderId, "流程变量 orderId 缺失");
        var order = orderRepository.findById(new OrderId(orderId))
                .orElseThrow(() -> new IllegalStateException("订单不存在 orderId=" + orderId));
        order.markRiskRejected();
        log.info("订单风控拒绝落账 orderId={}", orderId);
    }
}
