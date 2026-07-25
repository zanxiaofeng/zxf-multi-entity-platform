package com.zxf.platform.alpha;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.order.Money;
import com.zxf.platform.core.order.Order;
import com.zxf.platform.core.policy.PricingPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Alpha 专属计价：基础价之上叠加 13% 增值税。
 *
 * <p>约束（文档 5.3 / 7.1）：
 * <ul>
 *   <li>扩展点实现必须 {@code @Profile} 限定（ArchUnit 守护）；</li>
 *   <li>禁止调用 entity-beta 模块的任何类（Maven Enforcer 守护）；</li>
 *   <li>Alpha 专属的流程定义、校验规则、外部系统适配器同样放本模块，用 {@code @Profile("alpha")} 限定。</li>
 * </ul>
 */
@Component
@Profile("alpha")
public class AlphaPricingPolicy implements PricingPolicy {

    private static final BigDecimal BASE_UNIT_PRICE = new BigDecimal("100.00");
    private static final BigDecimal VAT_RATE = new BigDecimal("0.13");

    @Override
    public EntityType supports() {
        return EntityType.ALPHA;
    }

    @Override
    public Money calculate(Order order) {
        var base = BASE_UNIT_PRICE.multiply(BigDecimal.valueOf(order.getQuantity()));
        var total = base.multiply(BigDecimal.ONE.add(VAT_RATE)).setScale(2, RoundingMode.HALF_UP);
        return Money.cny(total);
    }
}
