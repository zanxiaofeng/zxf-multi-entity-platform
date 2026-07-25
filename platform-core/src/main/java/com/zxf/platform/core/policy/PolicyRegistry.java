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
                .collect(Collectors.toUnmodifiableMap(PricingPolicy::supports, Function.identity(),
                        (first, second) -> {
                            // 同一实体撞 key：误加第二个实现或双 profile 同时激活——
                            // 与下方"未装配"一样给出指向修复动作的消息，而非裸的 Duplicate key
                            throw new IllegalStateException(
                                    "实体 %s 装配了多个 PricingPolicy（%s / %s），同一实体必须有且仅有一个实现，请检查 @Profile 限定"
                                            .formatted(first.supports(),
                                                    first.getClass().getName(), second.getClass().getName()));
                        }));
        // 启动期防护：当前实体必须有且仅有一个实现装配，否则直接启动失败
        if (!pricingPolicies.containsKey(properties.entity())) {
            throw new IllegalStateException(
                    "当前实体 %s 未装配 PricingPolicy，请检查 SPRING_PROFILES_ACTIVE 与 platform.entity 是否一致"
                            .formatted(properties.entity()));
        }
    }

    public PricingPolicy pricing() {
        var entity = EntityContext.current();
        var policy = pricingPolicies.get(entity);
        // 部署级模型下不会走到（Filter 只设置为 platform.entity，构造器已校验）；
        // 混部演进（从 Header/Token 解析实体）后这是真实场景，当场 fail-loud 而非延迟 NPE
        if (policy == null) {
            throw new IllegalStateException(
                    "运行时实体 %s 未装配 PricingPolicy（已装配: %s），请检查实体上下文来源与装配是否一致"
                            .formatted(entity, pricingPolicies.keySet()));
        }
        return policy;
    }
}
