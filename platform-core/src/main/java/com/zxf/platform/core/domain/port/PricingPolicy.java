package com.zxf.platform.core.domain.port;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.domain.model.Money;
import com.zxf.platform.core.domain.model.Order;

/**
 * 差异行为的契约（扩展点，文档 5.2.4）。
 *
 * <p>所有按实体差异的行为都收敛为这样的接口。共享内核只依赖接口，不知道有几个实现。
 * 实现类必须声明 {@link #supports()} 且被 {@code @ForEntity} 限定（ArchUnit 守护，文档 8.3 / 5.10.1）。
 *
 * <p>纯契约：零框架注解、零 Spring import，技术细节全部留在适配层（文档 5.1.1 domain.port）。
 */
public interface PricingPolicy {

    EntityType supports();

    Money calculate(Order order);
}
