package com.zxf.platform.core.policy;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.PlatformProperties;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 策略注册表：替代 if-else，启动期 fail-fast（文档 5.2.5）。
 *
 * <p>{@code List<PricingPolicy>} 由 Spring 注入容器中全部实现。若配置漂移
 * （{@code platform.entity=alpha} 但忘了激活 alpha profile），容器里实现缺失——
 * 构造器内的校验让应用在<b>启动期立即失败</b>，而不是运行期第一次计价时才炸。
 */
@Component
public class PolicyRegistry {

    private final Map<EntityType, PricingPolicy> pricingPolicies;

    public PolicyRegistry(List<PricingPolicy> policies, PlatformProperties properties) {
        this.pricingPolicies = policies.stream()
                .collect(Collectors.toUnmodifiableMap(PricingPolicy::supports, Function.identity()));
        // 启动期防护：当前实体必须有且仅有一个实现装配，否则直接启动失败
        if (!pricingPolicies.containsKey(properties.entity())) {
            throw new IllegalStateException(
                    "当前实体 %s 未装配 PricingPolicy，请检查 SPRING_PROFILES_ACTIVE 与 platform.entity 是否一致"
                            .formatted(properties.entity()));
        }
    }

    public PricingPolicy pricing() {
        return pricingPolicies.get(EntityContext.current());
    }
}
