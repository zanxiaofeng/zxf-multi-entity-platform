package com.zxf.platform.beta;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.order.CreateOrderCommand;
import com.zxf.platform.core.order.Order;
import org.junit.jupiter.api.Test;

class BetaPricingPolicyTest {

    private final BetaPricingPolicy policy = new BetaPricingPolicy();

    @Test
    void 基础价95折() {
        var order = Order.from(new CreateOrderCommand("widget", 2));

        var money = policy.calculate(order);

        assertThat(money.amount()).isEqualByComparingTo("190.00"); // 200 * 0.95
        assertThat(money.currency()).isEqualTo("CNY");
    }

    @Test
    void supports返回Beta() {
        assertThat(policy.supports()).isEqualTo(EntityType.BETA);
    }
}
