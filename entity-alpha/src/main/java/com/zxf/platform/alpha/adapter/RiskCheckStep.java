package com.zxf.platform.alpha.adapter;

import com.zxf.platform.core.domain.model.OrderContext;
import com.zxf.platform.core.domain.port.OrderStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * Alpha 专属风控步骤（文档 5.8.1：步骤增删差异——Alpha 比 Beta 多一步风控）。
 * 管道第二步（{@code @Order(2)}），公共的 Schema 校验恒为首步。
 *
 * <p>行为型扩展点适配器：保持纯计算（文档 5.1.1 第 1 条）——生产接风控引擎时
 * 出站调用封装在本模块适配器内，不泄进 core。
 */
@Slf4j
@Component
@Order(2)
@Profile("alpha")
public class RiskCheckStep implements OrderStep {

    @Override
    public String name() {
        return "risk-check";
    }

    @Override
    public void execute(OrderContext ctx) {
        Assert.notNull(ctx, "订单上下文不能为 null");
        // demo 只打日志；生产实现调 Alpha 风控服务
        log.info("Alpha 风控检查 item={} quantity={}", ctx.order().item(), ctx.order().quantity());
    }
}
