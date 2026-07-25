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
 * Alpha 完整装配冒烟（文档 5.7）：真实启动全部自动配置（Web/JPA/Flyway），
 * 防交叉污染。仅在 {@code mvn -Palpha} 装配下运行（assembly.entity 由 app/pom.xml 注入）。
 *
 * <p>CI 装配矩阵：每个构建产物 × 对应 profile，各产物分别冒烟，互不背书。
 */
@SpringBootTest
@ActiveProfiles("alpha")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "alpha")
class AlphaAssemblySmokeTest {

    @Autowired
    private PolicyRegistry registry;

    @Autowired
    private List<PricingPolicy> policies;

    @BeforeEach
    void setUp() {
        EntityContext.set(EntityType.ALPHA); // 文档 5.6：单测直接 set/clear
    }

    @AfterEach
    void tearDown() {
        EntityContext.clear();
    }

    @Test
    void 只装配Alpha实现且注册表可解析() {
        assertThat(policies)
                .extracting(PricingPolicy::supports)
                .containsExactly(EntityType.ALPHA);
        assertThat(registry.pricing().supports()).isEqualTo(EntityType.ALPHA);
    }
}
