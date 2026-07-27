package com.zxf.platform.core.infrastructure.observation;

import com.zxf.platform.core.context.PlatformProperties;
import io.micrometer.core.instrument.MeterRegistry;
// SB4 模块化拆包：MeterRegistryCustomizer 从 actuate.autoconfigure.metrics 移到
// micrometer.metrics.autoconfigure（在独立模块 spring-boot-micrometer-metrics）
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 指标支柱实体打标（文档 5.11.1 已落地）：所有 {@link MeterRegistry} 自动携带
 * {@code entity} common tag，Splunk/Prometheus 告警按实体分别配置阈值，无需在
 * 每个埋点手工打标。
 *
 * <p>{@code commonTags} 在 registry 装配期应用一次，对所有未来 meter 生效——
 * 与 {@link EntityInfoContributor}（巡检事实）、{@code TraceIdFilter}（日志 trace）
 * 三支柱闭环文档 2.5 可观测性原则。
 *
 * <p>tag 基数：{@code entity} 是低基数 tag（值域 = {@code EntityType} 枚举），
 * 不会引爆 cardinality。
 */
@Configuration
public class MetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> entityCommonTags(PlatformProperties props) {
        return registry -> registry.config().commonTags("entity", props.entity().name());
    }
}
