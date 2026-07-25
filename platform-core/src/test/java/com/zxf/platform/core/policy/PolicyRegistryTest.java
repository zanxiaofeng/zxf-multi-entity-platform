package com.zxf.platform.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.PlatformProperties;
import com.zxf.platform.core.order.Money;
import com.zxf.platform.core.order.Order;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 文档 5.2.5：注册表解析与启动期 fail-fast 校验。 */
class PolicyRegistryTest {

    private final PricingPolicy alphaPolicy = stub(EntityType.ALPHA);
    private final PricingPolicy betaPolicy = stub(EntityType.BETA);

    @AfterEach
    void tearDown() {
        EntityContext.clear();
    }

    @Test
    void 按当前实体上下文解析策略() {
        var registry = new PolicyRegistry(
                List.of(alphaPolicy, betaPolicy), new PlatformProperties(EntityType.ALPHA));

        EntityContext.set(EntityType.BETA);
        assertThat(registry.pricing()).isSameAs(betaPolicy);

        EntityContext.set(EntityType.ALPHA);
        assertThat(registry.pricing()).isSameAs(alphaPolicy);
    }

    @Test
    void 当前实体未装配实现时启动失败() {
        // 配置漂移：platform.entity=beta 但容器里只有 alpha 实现（文档 5.2.5）
        assertThatThrownBy(() ->
                new PolicyRegistry(List.of(alphaPolicy), new PlatformProperties(EntityType.BETA)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未装配 PricingPolicy");
    }

    @Test
    void 上下文缺失时解析直接失败() {
        var registry = new PolicyRegistry(
                List.of(alphaPolicy), new PlatformProperties(EntityType.ALPHA));

        assertThatThrownBy(registry::pricing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EntityContext 未初始化");
    }

    private static PricingPolicy stub(EntityType type) {
        return new PricingPolicy() {
            @Override
            public EntityType supports() {
                return type;
            }

            @Override
            public Money calculate(Order order) {
                return Money.cny(BigDecimal.ONE);
            }
        };
    }
}
