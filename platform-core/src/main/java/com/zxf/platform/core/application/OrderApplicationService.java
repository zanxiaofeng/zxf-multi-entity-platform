package com.zxf.platform.core.application;

import com.zxf.platform.core.application.command.CreateOrderCommand;
import com.zxf.platform.core.application.port.OrderPipeline;
import com.zxf.platform.core.application.port.PolicyRegistry;
import com.zxf.platform.core.domain.event.OrderCreatedEvent;
import com.zxf.platform.core.domain.model.Order;
import com.zxf.platform.core.domain.model.OrderContext;
import com.zxf.platform.core.domain.model.OrderId;
import com.zxf.platform.core.domain.model.OutboxEvent;
import com.zxf.platform.core.domain.port.OrderApprovalPort;
import com.zxf.platform.core.domain.port.OrderRepository;
import com.zxf.platform.core.domain.port.OutboxRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * 订单应用服务（文档 5.1.1：用例编排，无业务规则，事务边界在此）。
 *
 * <p>编排顺序即主流程的外化：定价（策略端口）→ 管道步骤（装配差异）→ 持久化 →
 * 发起审批（引擎端口）→ 发布领域事件（审计等副作用 AFTER_COMMIT 消费，8.1 规则 11）。
 * 类内零实体判断——差异全部经端口路由。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderApplicationService {

    /** Outbox 聚合类型与事件类型常量（MQ 路由键，避免魔法字符串散落）。 */
    private static final String OUTBOX_AGGREGATE_ORDER = "ORDER";
    private static final String OUTBOX_EVENT_ORDER_CREATED = "ORDER_CREATED";

    private final OrderRepository repository;
    private final PolicyRegistry policies;
    private final OrderPipeline pipeline;
    private final OrderApprovalPort approval;
    private final OutboxRepository outboxRepository;
    private final ApplicationEventPublisher events;

    /**
     * 创建订单：定价（策略端口）→ 管道步骤（装配差异）→ 持久化 → 发起审批（引擎端口）
     * → 写 Outbox 事件（与业务表同事务，文档 7.7.2 组件 12）
     * → 发布领域事件（审计等副作用 AFTER_COMMIT 消费，文档 8.1 规则 11）。
     *
     * @param cmd 创建命令（不允许 {@code null}）
     * @return 已持久化订单（含标识与计价结果）
     */
    @Transactional
    public Order create(CreateOrderCommand cmd) {
        Assert.notNull(cmd, "创建命令不能为空");
        var order = Order.from(cmd.item(), cmd.quantity());
        order.priceTo(policies.priceFor(order));
        pipeline.run(new OrderContext(order));
        var saved = repository.save(order);
        var processInstanceId = approval.startApproval(saved);
        outboxRepository.save(new OutboxEvent(OUTBOX_AGGREGATE_ORDER, saved.id().value(), OUTBOX_EVENT_ORDER_CREATED, null));
        events.publishEvent(new OrderCreatedEvent(saved.id(), processInstanceId));
        log.info("订单已创建 orderId={} processInstanceId={}", saved.id().value(), processInstanceId);
        return saved;
    }

    /**
     * 按标识查询订单。
     *
     * @param id 订单标识（不允许 {@code null}）
     * @return 订单；不存在时 {@link Optional#empty()}
     */
    public Optional<Order> findById(OrderId id) {
        Assert.notNull(id, "订单标识不能为空");
        return repository.findById(id);
    }
}
