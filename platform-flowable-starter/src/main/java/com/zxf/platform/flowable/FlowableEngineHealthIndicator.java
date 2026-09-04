package com.zxf.platform.flowable;

import org.flowable.engine.RuntimeService;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Flowable 引擎健康指标（文档 7.7.2 组件 14）：在 {@code /actuator/health} 下输出
 * {@code flowable} 组件，{@code activeProcessInstances} 为当前活动流程实例数。
 *
 * <p>继承 {@link AbstractHealthIndicator}：异常由框架捕获并降级为 {@code DOWN}，
 * 不污染整个健康端点（单一组件故障不阻塞进程存活判定）。
 *
 * <p><b>SB 4.1 包迁移</b>：{@code HealthIndicator} / {@code AbstractHealthIndicator} /
 * {@code Health} 已从 {@code org.springframework.boot.actuate.health} 迁至
 * {@code org.springframework.boot.health.contributor}（独立模块 {@code spring-boot-health}），
 * 由 {@code spring-boot-starter-actuator} 传递引入。
 */
public class FlowableEngineHealthIndicator extends AbstractHealthIndicator {

    private final RuntimeService runtimeService;

    public FlowableEngineHealthIndicator(RuntimeService runtimeService) {
        this.runtimeService = runtimeService;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        var count = runtimeService.createProcessInstanceQuery().count();
        builder.up().withDetail("activeProcessInstances", count);
    }
}
