package com.zxf.platform.core.domain.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 订单聚合的契约负例（契约编程 §9：公开方法的非法输入必须显式失败）。 */
class OrderTest {

    @Test
    void 计价结果不能为null() {
        var order = Order.from("widget", 1);

        assertThatThrownBy(() -> order.priceTo(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("计价结果");
    }

    @Test
    void 未持久化订单无标识() {
        var order = Order.from("widget", 1);

        assertThatThrownBy(order::id)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未持久化");
    }

    @Test
    void 非法入参显式失败() {
        assertThatThrownBy(() -> Order.from(" ", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.from("widget", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
