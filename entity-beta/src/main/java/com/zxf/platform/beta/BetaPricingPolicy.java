package com.zxf.platform.beta;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.order.Money;
import com.zxf.platform.core.order.Order;
import com.zxf.platform.core.policy.PricingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Beta 专属计价：基础价 95 折（与 Alpha 结构完全对称，{@link #supports()} 返回 {@code BETA}）。
 *
 * <p>约束同 Alpha：{@code @Profile} 限定（ArchUnit 守护），禁止调用 entity-alpha 模块（Enforcer 守护）。
 */
@Component
@Profile("beta")
public class BetaPricingPolicy implements PricingPolicy {

    private static final BigDecimal BASE_UNIT_PRICE = new BigDecimal("100.00");
    private static final BigDecimal DISCOUNT_RATE = new BigDecimal("0.95");

    @Override
    public EntityType supports() {
        return EntityType.BETA;
    }

    @Override
    public Money calculate(Order order) {
        var base = BASE_UNIT_PRICE.multiply(BigDecimal.valueOf(order.getQuantity()));
        var total = base.multiply(DISCOUNT_RATE).setScale(2, RoundingMode.HALF_UP);
        return Money.cny(total);
    }
}
