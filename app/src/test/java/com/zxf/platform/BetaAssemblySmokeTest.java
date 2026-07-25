package com.zxf.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.policy.PolicyRegistry;
import com.zxf.platform.core.policy.PricingPolicy;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Beta 完整装配冒烟（文档 5.7）：与 Alpha 对称，仅在 {@code mvn -Pbeta} 装配下运行。
 */
@SpringBootTest
@ActiveProfiles("beta")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "beta")
class BetaAssemblySmokeTest {

    @Autowired
    private PolicyRegistry registry;

    @Autowired
    private List<PricingPolicy> policies;

    @BeforeEach
    void setUp() {
        EntityContext.set(EntityType.BETA);
    }

    @AfterEach
    void tearDown() {
        EntityContext.clear();
    }

    @Test
    void 只装配Beta实现且注册表可解析() {
        assertThat(policies)
                .extracting(PricingPolicy::supports)
                .containsExactly(EntityType.BETA);
        assertThat(registry.pricing().supports()).isEqualTo(EntityType.BETA);
    }
}
