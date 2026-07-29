package com.zxf.platform.flowable.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Flowable 健康检查配置（{@code platform.flowable.health.*}，文档 7.7.2 组件 14）。
 *
 * <p>默认启用；业务方在 {@code application.yaml} 设 {@code platform.flowable.health.enabled=false}
 * 可关闭（如不希望暴露流程实例数到 actuator 端点）。
 */
@ConfigurationProperties(prefix = "platform.flowable.health")
public record FlowableHealthProperties(@DefaultValue("true") boolean enabled) {
}
