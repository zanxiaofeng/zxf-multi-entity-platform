package com.zxf.platform.core.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * 平台级配置。
 *
 * <p>{@code platform.entity} 是当前部署服务实体的<b>唯一事实来源</b>（文档 5.3）：
 * 必须与 {@code SPRING_PROFILES_ACTIVE}、构建产物（Maven profile）三者一致，
 * 漂移由启动期校验与 CI 装配矩阵兜底（文档 6.3）。
 *
 * <p>record 构造器绑定（文档 5.2.2）：不可变、无需 {@code @Validated} 切面——
 * 必填校验收敛在紧凑构造器里，绑定即校验。
 */
@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(EntityType entity) {

    public PlatformProperties {
        Assert.notNull(entity, "platform.entity 必须配置");
    }
}
