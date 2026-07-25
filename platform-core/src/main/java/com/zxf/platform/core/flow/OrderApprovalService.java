package com.zxf.platform.core.flow;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.order.Order;
import java.util.Map;
import org.flowable.engine.RuntimeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单审批发起（文档 7.1）。
 *
 * <p>本包（{@code core.flow}）属<b>基础设施适配层</b>：内核只依赖 Flowable 引擎 API，
 * 不依赖任何具体流程定义；流程定义 key（{@value #ORDER_APPROVAL_KEY}）构成内核与实体模块
 * 之间的契约，与 SPI 接口同级，纳入契约治理。
 *
 * <p>代码从"流程编排者"退化为"任务实现者"：没有
 * {@code if (entity == A) { 走三步审批 } else { 走五步 }}，只有按 key 发起实例。
 *
 * <p>注：本类作为引擎适配器接触 {@link EntityContext}（把 entity 写入流程变量，作异步
 * Job 线程的双保险，文档 7.3③），与 Filter 同属基础设施场景；业务服务（{@code core.service}）
 * 仍禁止触碰静态上下文（ArchUnit 守护）。
 */
@Service
public class OrderApprovalService {

    /** 内核与实体模块之间的契约：两个实体各自部署同 key、不同拓扑的流程定义（文档 7.2）。 */
    public static final String ORDER_APPROVAL_KEY = "order-approval";

    private final RuntimeService runtimeService;

    public OrderApprovalService(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    /**
     * 发起订单审批流程实例，返回流程实例 ID。
     * 与调用方业务操作同事务（{@code @Transactional} + 同一 DataSource，文档 7.2 落地要点 5）。
     */
    @Transactional
    public String startApproval(Order order) {
        // 流程变量只放轻量标识，不放实体对象（避免序列化与历史表膨胀）；
        // delegate 内按 orderId 重新加载领域对象；entity 变量供 delegate 基类在
        // Job 线程重建上下文（文档 7.3③ 双保险，见 EntityContextAwareDelegate）
        return runtimeService.startProcessInstanceByKey(
                        ORDER_APPROVAL_KEY,
                        String.valueOf(order.getId()),
                        Map.of("orderId", order.getId(),
                                EntityContextAwareDelegate.ENTITY_VARIABLE, EntityContext.current().name()))
                .getProcessInstanceId();
    }
}
