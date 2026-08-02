package com.zxf.platform.core.application.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.context.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Beta 完整装配冒烟（文档 5.7 / 5.8.1）：与 Alpha 对称，仅在 {@code mvn -Pbeta} 装配下运行。
 */
@SpringBootTest
@ActiveProfiles("beta")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "beta")
// 每测试类独立 H2 库：原因见 AlphaOrderApiEndToEndTest 同位置注释
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:beta-assembly-smoke-db;DB_CLOSE_DELAY=-1")
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
