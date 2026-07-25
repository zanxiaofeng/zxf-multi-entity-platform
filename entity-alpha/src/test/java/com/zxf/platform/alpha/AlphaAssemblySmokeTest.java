package com.zxf.platform.alpha;

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
 * Alpha 装配冒烟（文档 5.7）：防交叉污染——只装配 Alpha 实现且注册表可解析。
 *
 * <p>轻量 Spring 上下文（不依赖 Boot 自动配置），随 entity-alpha 模块构建永远运行；
 * 完整启动级冒烟见 app 模块的 {@code @SpringBootTest} 矩阵。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AlphaAssemblySmokeTest.TestAssembly.class)
@ActiveProfiles("alpha")
@TestPropertySource(properties = "platform.entity=alpha")
class AlphaAssemblySmokeTest {

    @Configuration
    @EnableConfigurationProperties(PlatformProperties.class)
    @Import(PolicyRegistry.class)
    @ComponentScan("com.zxf.platform.alpha")
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
    void 只装配Alpha实现且注册表可解析() {
        assertThat(policies)
                .extracting(PricingPolicy::supports)
                .containsExactly(EntityType.ALPHA);

        EntityContext.set(EntityType.ALPHA);
        assertThat(registry.pricing().supports()).isEqualTo(EntityType.ALPHA);
    }
}
