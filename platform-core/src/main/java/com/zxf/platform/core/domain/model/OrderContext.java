package com.zxf.platform.core.domain.model;

import org.springframework.util.Assert;

/**
 * 管道上下文（文档 5.8.1）：{@code OrderStep} 之间传递的订单载体。
 * 当前只有订单一个载荷；步骤需要共享中间产物时在此扩展（保持不可变）。
 */
public record OrderContext(Order order) {

    public OrderContext {
        Assert.notNull(order, "order 不能为空");
    }
}
