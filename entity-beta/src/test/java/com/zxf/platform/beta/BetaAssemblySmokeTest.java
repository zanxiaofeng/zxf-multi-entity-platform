package com.zxf.platform.beta;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.application.port.PolicyRegistry;
import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.PlatformProperties;
import com.zxf.platform.core.domain.model.Money;
import com.zxf.platform.core.domain.model.Order;
import com.zxf.platform.core.domain.port.OrderStep;
import com.zxf.platform.core.domain.port.PricingPolicy;
import com.zxf.platform.core.domain.service.AbstractDocumentGenerator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Beta 装配冒烟（文档 5.7）：与 Alpha 完全对称——只装配 Beta 实现且注册表可按当前实体计价。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = BetaAssemblySmokeTest.TestAssembly.class)
@ActiveProfiles("beta")
@TestPropertySource(properties = "platform.entity=beta")
class BetaAssemblySmokeTest {

    @Configuration
    @EnableConfigurationProperties(PlatformProperties.class)
    @Import(PolicyRegistry.class)
    @ComponentScan("com.zxf.platform.beta")
    static class TestAssembly {
    }

    @Autowired
    private List<PricingPolicy> policies;

    @Autowired
    private List<OrderStep> orderSteps;

    @Autowired
    private List<AbstractDocumentGenerator> documentGenerators;

    @Autowired
    private PolicyRegistry registry;

    @AfterEach
    void tearDown() {
        EntityContext.clear();
    }

    @Test
    void 只装配Beta实现且注册表可按当前实体计价() {
        assertThat(policies)
                .extracting(PricingPolicy::supports)
                .containsExactly(EntityType.BETA);

        EntityContext.set(EntityType.BETA);
        assertThat(registry.priceFor(Order.from("widget", 1)))
                .isEqualTo(Money.cny("95.00")); // 100 * 0.95
    }

    @Test
    void 装配Beta专属管道步骤与单据生成器() {
        assertThat(orderSteps)
                .extracting(OrderStep::name)
                .containsExactly("audit-extra");
        assertThat(documentGenerators).hasSize(1);
    }
}
