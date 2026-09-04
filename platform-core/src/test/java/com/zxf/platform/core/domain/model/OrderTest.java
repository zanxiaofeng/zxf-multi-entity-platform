package com.zxf.platform.core.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
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
    void 已计价订单重复计价显式失败() {
        // 评审修复 M6：priceTo 单次不变量——编排只应调用一次，误调用（改价面）当场显式失败
        var order = Order.from("widget", 1);
        order.priceTo(Money.cny("113"));

        assertThatThrownBy(() -> order.priceTo(Money.cny("226")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复计价");
    }

    @Test
    void 新订单初始状态为已创建() {
        assertThat(Order.from("widget", 1).status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void 风控拒绝为单向终态转移() {
        // 评审修复 M3：CREATED → RISK_REJECTED 可转移；终态重复拒绝属编排缺陷
        var order = Order.from("risk-widget", 1);
        order.markRiskRejected();

        assertThat(order.status()).isEqualTo(OrderStatus.RISK_REJECTED);
        assertThatThrownBy(order::markRiskRejected)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不允许风控拒绝");
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
