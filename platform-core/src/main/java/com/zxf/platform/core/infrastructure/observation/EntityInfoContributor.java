package com.zxf.platform.core.infrastructure.observation;

import com.zxf.platform.core.context.EntityCapability;
import com.zxf.platform.core.context.PlatformProperties;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

/**
 * 运行期漂移检测（文档 6.3 第 3 条防线 + 5.10.2 能力自描述）：{@code /actuator/info}
 * 输出当前实体 + 装配的模块能力清单（版本、覆盖的扩展点），接入监控巡检，发现
 * "实体 A 的命名空间里跑着实体 B 的镜像"立即告警。
 *
 * <p>{@code List<EntityCapability>} 由 Spring 注入容器中全部实现——单一 profile 下
 * 只有当前装配实体对应的那一个被激活（{@code @ForEntity} 限定），零依赖到 1 个 bean
 * 都安全（Spring 的 {@code List<T>} 注入吸收空 List）。
 */
@Component
@RequiredArgsConstructor
public class EntityInfoContributor implements InfoContributor {

    private final PlatformProperties properties;
    private final List<EntityCapability> capabilities;

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("entity", properties.entity());
        // 汇总能力清单：当前实体对应模块的版本与覆盖的扩展点（文档 5.10.2）
        builder.withDetail("modules", capabilities.stream()
                .map(c -> Map.of(
                        "name", c.entity().name().toLowerCase(),
                        "version", c.moduleVersion(),
                        "policies", c.providedPolicies().stream()
                                .map(Class::getName).sorted().toList()))
                .toList());
    }
}
