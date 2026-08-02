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
 * Alpha 完整装配冒烟（文档 5.7 / 5.8.1）：真实启动全部自动配置（Web/JPA/Flyway），
 * 防交叉污染；管道步骤序列逐字比对（步骤名有序列表，含公共 Schema 步骤）。
 * 仅在 {@code mvn -Palpha} 装配下运行（assembly.entity 由 app/pom.xml 注入）。
 *
 * <p>放在被测类同包（application.port 的 app 测试源集）：hasPolicy/stepNames 为包私有
 * 装配查询（文档 5.9 军规 8——不让测试需求倒逼封装破坏）。
 *
 * <p>CI 装配矩阵：每个构建产物 × 对应 profile，各产物分别冒烟，互不背书。
 */
@SpringBootTest
@ActiveProfiles("alpha")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "alpha")
// 每测试类独立 H2 库：原因见 AlphaOrderApiEndToEndTest 同位置注释
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:alpha-assembly-smoke-db;DB_CLOSE_DELAY=-1")
class AlphaAssemblySmokeTest {

    @Autowired
    private PolicyRegistry registry;

    @Autowired
    private OrderPipeline pipeline;

    @Test
    void 只装配Alpha定价实现() {
        assertThat(registry.hasPolicy(EntityType.ALPHA)).isTrue();
        assertThat(registry.hasPolicy(EntityType.BETA)).isFalse();
    }

    @Test
    void 管道步骤序列为公共校验加Alpha风控() {
        assertThat(pipeline.stepNames()).containsExactly("schema-validation", "risk-check");
    }
}
