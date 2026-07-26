package com.zxf.platform.core.application.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.context.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Beta 完整装配冒烟（文档 5.7 / 5.8.1）：与 Alpha 对称，仅在 {@code mvn -Pbeta} 装配下运行。
 */
@SpringBootTest
@ActiveProfiles("beta")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "beta")
class BetaAssemblySmokeTest {

    @Autowired
    private PolicyRegistry registry;

    @Autowired
    private OrderPipeline pipeline;

    @Test
    void 只装配Beta定价实现() {
        assertThat(registry.hasPolicy(EntityType.BETA)).isTrue();
        assertThat(registry.hasPolicy(EntityType.ALPHA)).isFalse();
    }

    @Test
    void 管道步骤序列为公共校验加Beta审计() {
        assertThat(pipeline.stepNames()).containsExactly("schema-validation", "audit-extra");
    }
}
