package com.zxf.platform.beta;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.PlatformProperties;
import com.zxf.platform.core.policy.PolicyRegistry;
import com.zxf.platform.core.policy.PricingPolicy;
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
 * Beta 装配冒烟（文档 5.7）：与 Alpha 完全对称——只装配 Beta 实现且注册表可解析。
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
    private PolicyRegistry registry;

    @AfterEach
    void tearDown() {
        EntityContext.clear();
    }

    @Test
    void 只装配Beta实现且注册表可解析() {
        assertThat(policies)
                .extracting(PricingPolicy::supports)
                .containsExactly(EntityType.BETA);

        EntityContext.set(EntityType.BETA);
        assertThat(registry.pricing().supports()).isEqualTo(EntityType.BETA);
    }
}
