package com.zxf.platform.beta.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.domain.model.Order;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class BetaPricingPolicyTest {

    private final BetaPricingPolicy policy = new BetaPricingPolicy();

    @Test
    void 基础价95折() {
        var order = Order.from("widget", 2);

        var money = policy.calculate(order);

        assertThat(money.amount()).isEqualByComparingTo("190.00"); // 200 * 0.95
        assertThat(money.currency()).isEqualTo(Currency.getInstance("CNY"));
    }

    @Test
    void supports返回Beta() {
        assertThat(policy.supports()).isEqualTo(EntityType.BETA);
    }
}
