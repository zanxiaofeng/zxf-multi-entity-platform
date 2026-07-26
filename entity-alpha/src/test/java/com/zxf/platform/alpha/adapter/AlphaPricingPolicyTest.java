package com.zxf.platform.alpha.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.domain.model.Order;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class AlphaPricingPolicyTest {

    private final AlphaPricingPolicy policy = new AlphaPricingPolicy();

    @Test
    void 基础价叠加13增值税() {
        var order = Order.from("widget", 2);

        var money = policy.calculate(order);

        assertThat(money.amount()).isEqualByComparingTo("226.00"); // 200 * 1.13
        assertThat(money.currency()).isEqualTo(Currency.getInstance("CNY"));
    }

    @Test
    void supports返回Alpha() {
        assertThat(policy.supports()).isEqualTo(EntityType.ALPHA);
    }
}
