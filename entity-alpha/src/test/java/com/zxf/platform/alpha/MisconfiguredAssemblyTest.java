package com.zxf.platform.alpha;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zxf.platform.core.application.port.PolicyRegistry;
import com.zxf.platform.core.context.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 负例（文档 5.7 / 6.3）：profile 与 {@code platform.entity} 漂移时启动必须失败——
 * 三个开关（{@code platform.entity} ↔ {@code SPRING_PROFILES_ACTIVE} ↔ 构建产物）不允许双轨漂移。
 */
class MisconfiguredAssemblyTest {

    @Configuration
    @EnableConfigurationProperties(PlatformProperties.class)
    @Import(PolicyRegistry.class)
    @ComponentScan("com.zxf.platform.alpha")
    static class TestAssembly {
    }

    @Test
    void profile与entity不一致时上下文启动失败() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("alpha"); // alpha profile 激活
            TestPropertyValues.of("platform.entity=beta").applyTo(context.getEnvironment()); // 但 entity 指向 beta
            context.register(TestAssembly.class);

            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未装配 PricingPolicy");
        }
    }
}
