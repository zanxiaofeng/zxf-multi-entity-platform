package com.zxf.platform.beta;

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
 * 负例（文档 5.7 / 6.3）：profile 与 {@code platform.entity} 漂移时启动必须失败。
 */
class MisconfiguredAssemblyTest {

    @Configuration
    @EnableConfigurationProperties(PlatformProperties.class)
    @Import(PolicyRegistry.class)
    @ComponentScan("com.zxf.platform.beta")
    static class TestAssembly {
    }

    @Test
    void profile与entity不一致时上下文启动失败() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("beta"); // beta profile 激活
            TestPropertyValues.of("platform.entity=alpha").applyTo(context.getEnvironment()); // 但 entity 指向 alpha
            context.register(TestAssembly.class);

            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未装配 PricingPolicy");
        }
    }
}
