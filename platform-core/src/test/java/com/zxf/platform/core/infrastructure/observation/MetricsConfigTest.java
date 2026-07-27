package com.zxf.platform.core.infrastructure.observation;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.PlatformProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * 文档 5.11.1 落地契约：{@link MetricsConfig#entityCommonTags} 给所有 meter 打
 * {@code entity} common tag——用 {@link SimpleMeterRegistry}（micrometer-core 自带，
 * 避免引入 prometheus registry 的测试依赖）验证。
 */
class MetricsConfigTest {

    @Test
    void 所有meter自动携带entity标签() {
        var registry = new SimpleMeterRegistry();
        var props = new PlatformProperties(EntityType.ALPHA);

        // 应用 customizer（模拟 Spring Boot 装配期行为）
        new MetricsConfig().entityCommonTags(props).customize(registry);

        // 注册一个测试 meter，断言其自动带 entity=ALPHA 标签
        registry.counter("test.counter").increment();

        assertThat(registry.find("test.counter").counters())
                .as("commonTags 必须应用到所有未来注册的 meter")
                .hasSize(1)
                .first()
                .satisfies(meter -> assertThat(meter.getId().getTags())
                        .anyMatch(tag -> tag.getKey().equals("entity") && tag.getValue().equals("ALPHA")));
    }
}
