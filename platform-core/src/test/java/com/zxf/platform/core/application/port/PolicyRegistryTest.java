package com.zxf.platform.core.application.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.PlatformProperties;
import com.zxf.platform.core.domain.model.Money;
import com.zxf.platform.core.domain.model.Order;
import com.zxf.platform.core.domain.port.PricingPolicy;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 文档 5.2.5 / 5.9：注册表计价路由、启动期 fail-fast 校验与包私有装配查询。 */
class PolicyRegistryTest {

    private static final Order ANY_ORDER = Order.from("widget", 1);

    private final PricingPolicy alphaPolicy = stub(EntityType.ALPHA, Money.cny("1.00"));
    private final PricingPolicy betaPolicy = stub(EntityType.BETA, Money.cny("2.00"));

    @AfterEach
    void tearDown() {
        EntityContext.clear();
    }

    @Test
    void 按当前实体上下文路由计价() {
        var registry = new PolicyRegistry(
                List.of(alphaPolicy, betaPolicy), new PlatformProperties(EntityType.ALPHA));

        EntityContext.set(EntityType.BETA);
        assertThat(registry.priceFor(ANY_ORDER)).isEqualTo(Money.cny("2.00"));

        EntityContext.set(EntityType.ALPHA);
        assertThat(registry.priceFor(ANY_ORDER)).isEqualTo(Money.cny("1.00"));
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
    void 同一实体装配多个实现时启动失败且消息可定位() {
        // 误加第二个 @Profile("alpha") 实现 / 双 profile 同时激活：报错应指向修复动作
        assertThatThrownBy(() ->
                new PolicyRegistry(List.of(alphaPolicy, stub(EntityType.ALPHA, Money.cny("3.00"))),
                        new PlatformProperties(EntityType.ALPHA)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ALPHA")
                .hasMessageContaining("多个 PricingPolicy");
    }

    @Test
    void 上下文缺失时计价直接失败() {
        var registry = new PolicyRegistry(
                List.of(alphaPolicy), new PlatformProperties(EntityType.ALPHA));

        assertThatThrownBy(() -> registry.priceFor(ANY_ORDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EntityContext 未初始化");
    }

    @Test
    void 上下文实体未装配实现时计价显式失败() {
        // 混部场景防御（文档 9）：Header 解析出未装配的实体时当场报错，而非延迟 NPE
        var registry = new PolicyRegistry(
                List.of(alphaPolicy), new PlatformProperties(EntityType.ALPHA));

        EntityContext.set(EntityType.BETA);
        assertThatThrownBy(() -> registry.priceFor(ANY_ORDER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BETA")
                .hasMessageContaining("未装配 PricingPolicy");
    }

    @Test
    void hasPolicy反映装配结果() {
        // 包私有装配查询（文档 5.9 军规 8）：冒烟测试与被测类同包即可访问，不破坏封装
        var registry = new PolicyRegistry(
                List.of(alphaPolicy), new PlatformProperties(EntityType.ALPHA));

        assertThat(registry.hasPolicy(EntityType.ALPHA)).isTrue();
        assertThat(registry.hasPolicy(EntityType.BETA)).isFalse();
    }

    private static PricingPolicy stub(EntityType type, Money price) {
        return new PricingPolicy() {
            @Override
            public EntityType supports() {
                return type;
            }

            @Override
            public Money calculate(Order order) {
                return price;
            }
        };
    }
}
