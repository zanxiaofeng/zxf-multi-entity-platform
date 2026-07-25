package com.zxf.platform.core.context;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 平台级配置。
 *
 * <p>{@code platform.entity} 是当前部署服务实体的<b>唯一事实来源</b>（文档 5.3）：
 * 必须与 {@code SPRING_PROFILES_ACTIVE}、构建产物（Maven profile）三者一致，
 * 漂移由启动期校验与 CI 装配矩阵兜底（文档 6.3）。
 *
 * <p>Spring Boot 4 起 public 字段宽松绑定已移除，统一使用构造器绑定（record）或 setter（文档 5.0）。
 */
@Validated
@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(@NotNull EntityType entity) {
}
