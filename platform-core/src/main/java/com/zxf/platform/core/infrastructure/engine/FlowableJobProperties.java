package com.zxf.platform.core.infrastructure.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Flowable Job 执行器线程池配置（{@code platform.flowable-job.*}）。
 *
 * <p>引擎自有线程，不经 Web Filter / {@code @Async} 传播链。默认值面向 demo；生产按实体
 * 部署需要差异化池大小时，在 {@code application-{entity}.yaml} 覆盖对应键。
 */
@ConfigurationProperties(prefix = "platform.flowable-job")
public record FlowableJobProperties(
        @DefaultValue("2") int corePoolSize,
        @DefaultValue("4") int maxPoolSize,
        @DefaultValue("100") int queueCapacity) {
}
