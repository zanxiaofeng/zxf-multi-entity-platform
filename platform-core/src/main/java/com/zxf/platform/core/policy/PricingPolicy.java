package com.zxf.platform.core.policy;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.order.Money;
import com.zxf.platform.core.order.Order;

/**
 * 差异行为的契约（扩展点，文档 5.2.4）。
 *
 * <p>所有按实体差异的行为都收敛为这样的接口。共享内核只依赖接口，不知道有几个实现。
 * 实现类必须声明 {@link #supports()} 且被 {@code @Profile} 限定（ArchUnit 守护，文档 7.1.2）。
 */
public interface PricingPolicy {

    EntityType supports();

    Money calculate(Order order);
}
