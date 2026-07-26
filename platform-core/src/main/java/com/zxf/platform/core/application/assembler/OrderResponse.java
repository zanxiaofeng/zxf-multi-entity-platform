package com.zxf.platform.core.application.assembler;

import com.zxf.platform.core.domain.model.Money;
import com.zxf.platform.core.domain.model.Order;
import org.springframework.util.Assert;

/** 订单对外响应 DTO：标识以字符串形式暴露（领域标识 {@code OrderId} 不外泄，文档 5.9）。 */
public record OrderResponse(String id, String item, int quantity, Money price) {

    /**
     * 从持久化订单装配响应。
     *
     * @param order 已持久化订单（{@code id} 必须存在）
     * @return 对外响应 DTO
     */
    public static OrderResponse from(Order order) {
        Assert.notNull(order, "订单不能为 null");
        return new OrderResponse(order.id().value(), order.item(), order.quantity(), order.price());
    }
}
