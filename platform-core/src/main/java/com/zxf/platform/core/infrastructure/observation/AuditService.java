package com.zxf.platform.core.infrastructure.observation;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.domain.event.OrderCreatedEvent;
import com.zxf.platform.core.domain.port.AuditPort;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.Assert;

/**
 * 审计：共享内核的横切能力之一（文档 2.3），{@link AuditPort} 的 observation 实现。
 *
 * <p>事件驱动的副作用（文档 8.1 规则 11）：业务服务只发事件，本类在事务提交后
 * （{@code AFTER_COMMIT}）才消费——事务回滚不产生审计记录。监听器 {@code @Async}
 * 异步执行，实体上下文经 {@code TaskDecorator} 传播（文档 5.2.3）。
 *
 * <p>demo 用内存审计轨迹（便于测试断言）；生产替换为审计库 / 审计消息。
 */
@Slf4j
@Component
public class AuditService implements AuditPort {

    private final List<AuditEntry> trail = new CopyOnWriteArrayList<>();

    /** 订单创建审计：事务提交后才记录；{@code @Async} 路径上下文经 TaskDecorator 传播。 */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        record("ORDER_CREATED", "orderId=" + event.orderId().value() + " processInstanceId=" + event.processInstanceId());
    }

    /**
     * 记录一条审计（同步方法：线程与上下文由调用方负责——Web 请求线程、
     * {@code @Async} 监听器线程，或已重建上下文的 delegate Job 线程）。
     * 上下文缺失是允许的基础设施场景，用 {@code currentOrNull}。
     */
    @Override
    public void record(String action, String detail) {
        Assert.hasText(action, "审计动作不能为空");
        Assert.notNull(detail, "审计明细不能为 null");
        EntityType entity = EntityContext.currentOrNull();
        trail.add(new AuditEntry(entity, action, detail));
        log.info("audit action={} detail={}", action, detail); // MDC 已带 entity 标
    }

    /** demo 用：返回审计轨迹快照。 */
    public List<AuditEntry> trail() {
        return List.copyOf(trail);
    }

    public record AuditEntry(@Nullable EntityType entity, String action, String detail) {
    }
}
