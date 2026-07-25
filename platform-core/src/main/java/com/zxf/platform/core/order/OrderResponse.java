package com.zxf.platform.core.order;

/** 订单对外响应 DTO。 */
public record OrderResponse(Long id, String item, int quantity, Money price) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getItem(), order.getQuantity(), order.getPrice());
    }
}
