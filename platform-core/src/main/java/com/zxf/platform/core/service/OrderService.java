package com.zxf.platform.core.service;

import com.zxf.platform.core.audit.AuditService;
import com.zxf.platform.core.flow.OrderApprovalService;
import com.zxf.platform.core.order.CreateOrderCommand;
import com.zxf.platform.core.order.Order;
import com.zxf.platform.core.order.OrderRepository;
import com.zxf.platform.core.policy.PolicyRegistry;
import java.util.Optional;
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
    private final AuditService audit;

    public OrderService(OrderRepository repository, PolicyRegistry policies,
                        OrderApprovalService approval, AuditService audit) {
        this.repository = repository;
        this.policies = policies;
        this.approval = approval;
        this.audit = audit;
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
        // 横切：审计（异步，实体上下文经 TaskDecorator 传播，文档 5.2.3）
        audit.record("ORDER_CREATED", "orderId=" + saved.getId() + " processInstanceId=" + processInstanceId);
        return saved;
    }

    public Optional<Order> find(long id) {
        return repository.findById(id);
    }
}
