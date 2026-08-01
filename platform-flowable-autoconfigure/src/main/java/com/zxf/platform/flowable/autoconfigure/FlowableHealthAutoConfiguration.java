package com.zxf.platform.flowable.autoconfigure;

import org.flowable.engine.RuntimeService;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Flowable 健康检查自动配置（文档 7.7.2 组件 14）。
 *
 * <p>条件组合（Spring Boot starter 标准模式）：
 * <ul>
 *   <li>{@link ConditionalOnClass} {@code RuntimeService} —— 类路径无 Flowable 时不激活，
 *       使本 autoconfigure 在无 Flowable 工程中零开销</li>
 *   <li>{@link ConditionalOnProperty} {@code platform.flowable.health.enabled}（默认 true）——
 *       业务方可整体关闭</li>
 *   <li>{@link ConditionalOnMissingBean} {@code flowableHealthIndicator} ——
 *       业务方可自定义同名 bean 覆盖（如采集更多引擎指标）</li>
 * </ul>
 *
 * <p>通过 {@code AutoConfiguration.imports} 注册，被 Spring Boot 自动装配扫描器发现。
 */
@AutoConfiguration
@ConditionalOnClass(RuntimeService.class)
@ConditionalOnProperty(prefix = "platform.flowable.health", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FlowableHealthProperties.class)
public class FlowableHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "flowableHealthIndicator")
    public HealthIndicator flowableHealthIndicator(RuntimeService runtimeService) {
        return new FlowableEngineHealthIndicator(runtimeService);
    }
}
