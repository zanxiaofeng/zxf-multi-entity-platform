package com.zxf.platform.core.domain.port;

import com.zxf.platform.core.domain.model.OrderContext;

/**
 * 管道步骤接口（文档 5.8.1）：解决"流程步骤增删"差异（A 多一步风控、B 多一步审计）。
 *
 * <p>步骤列表由装配决定（{@code @ForEntity} + {@code @Order}），核心层零实体判断。
 * 与策略（"同一件事算法不同"）互补——拿不准归属时默认策略（5.8.4 分工三角）。
 */
public interface OrderStep {

    String name();

    void execute(OrderContext ctx);
}
