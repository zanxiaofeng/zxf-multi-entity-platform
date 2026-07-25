package com.zxf.platform.core.service;

import com.zxf.platform.core.flow.OrderApprovalService;
import com.zxf.platform.core.order.CreateOrderCommand;
import com.zxf.platform.core.order.Order;
import com.zxf.platform.core.order.OrderCreatedEvent;
import com.zxf.platform.core.order.OrderRepository;
import com.zxf.platform.core.policy.PolicyRegistry;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 共享内核中的通用逻辑（文档 5.5）。
 *
 * <p><b>硬性检查项：本类不允许出现任何实体判断</b>（不引用 {@code EntityType} /
 * {@code EntityContext} / {@code platform.entity}，由 ArchUnit 守护，文档 8.1.1）。
 * 差异一律委托扩展点；流程拓扑差异外置为 BPMN，本类只按契约 key 发起实例（文档 7.1）。
 */
@Service
public class OrderService {

    private final OrderRepository repository;
    private final PolicyRegistry policies;
    private final OrderApprovalService approval;
    private final ApplicationEventPublisher events;

    public OrderService(OrderRepository repository, PolicyRegistry policies,
                        OrderApprovalService approval, ApplicationEventPublisher events) {
        this.repository = repository;
        this.policies = policies;
        this.approval = approval;
        this.events = events;
    }

    @Transactional
    public Order create(CreateOrderCommand cmd) {
        var order = Order.from(cmd);                     // 通用：结构
        var price = policies.pricing().calculate(order); // 差异：委托扩展点
        order.priceTo(price);
        var saved = repository.save(order);
        // 流程拓扑差异外置：按 key 发起审批实例，不关心是哪个实体的拓扑（文档 7.1/7.2），
        // 与本事务同库同数据源原子提交（文档 7.2 落地要点 5）
        var processInstanceId = approval.startApproval(saved);
        // 横切：审计走 AFTER_COMMIT 事件（文档 8.1 规则 11）——回滚不留审计；
        // 监听器 @Async 异步执行，实体上下文经 TaskDecorator 传播（文档 5.2.3）
        events.publishEvent(new OrderCreatedEvent(saved.getId(), processInstanceId));
        return saved;
    }

    public Optional<Order> find(long id) {
        return repository.findById(id);
    }
}
