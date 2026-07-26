package com.zxf.platform.beta.adapter;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.ForEntity;
import com.zxf.platform.core.domain.model.OrderContext;
import com.zxf.platform.core.domain.port.OrderStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * Beta 专属审计步骤（文档 5.8.1：步骤增删差异——Beta 比 Alpha 多一步审计留痕，
 * 与 Alpha 的 {@code RiskCheckStep} 构成同位置（{@code @Order(2)}）的对照）。
 */
@Slf4j
@Component
@Order(2)
@ForEntity(EntityType.BETA)
public class AuditExtraStep implements OrderStep {

    @Override
    public String name() {
        return "audit-extra";
    }

    @Override
    public void execute(OrderContext ctx) {
        Assert.notNull(ctx, "订单上下文不能为 null");
        // demo 只打日志；生产实现写 Beta 审计扩展（对照 beta_audit_extra 表）
        log.info("Beta 审计留痕 item={} quantity={}", ctx.order().item(), ctx.order().quantity());
    }
}
