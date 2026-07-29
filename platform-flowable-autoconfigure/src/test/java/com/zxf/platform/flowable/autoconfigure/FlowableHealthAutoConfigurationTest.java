package com.zxf.platform.flowable.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@link FlowableHealthAutoConfiguration} 条件分支覆盖（文档 7.7.2 组件 14）。
 *
 * <p>使用 {@link ApplicationContextRunner} + {@link FilteredClassLoader} 模拟
 * 「类路径无 RuntimeService」「显式禁用」等场景，验证条件组合是否正确收敛。
 */
class FlowableHealthAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FlowableHealthAutoConfiguration.class));

    @Test
    void 有RuntimeService且默认启用时注册健康指标() {
        runner.withBean(RuntimeService.class, () -> mock(RuntimeService.class))
                .run(context -> assertThat(context).hasSingleBean(HealthIndicator.class));
    }

    @Test
    void 无RuntimeService时不注册() {
        runner.withClassLoader(new FilteredClassLoader(RuntimeService.class))
                .run(context -> assertThat(context).doesNotHaveBean(HealthIndicator.class));
    }

    @Test
    void 显式禁用时不注册() {
        runner.withBean(RuntimeService.class, () -> mock(RuntimeService.class))
                .withPropertyValues("platform.flowable.health.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HealthIndicator.class));
    }
}
