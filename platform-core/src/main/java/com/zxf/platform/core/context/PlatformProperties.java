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
 * <p>record 构造器绑定（文档 5.2.2）+ {@code @Validated} 声明式校验
 * （validation.md §2.8）：绑定后由 {@code ConfigurationPropertiesBinder} 执行
 * Bean Validation，配置缺失时启动即失败（Fail Fast），错误信息含属性名。
 */
@Validated
@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(@NotNull(message = "platform.entity 必须配置") EntityType entity) {
}
