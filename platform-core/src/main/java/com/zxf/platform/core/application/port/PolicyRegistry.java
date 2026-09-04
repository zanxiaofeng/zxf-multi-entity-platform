package com.zxf.platform.core.application.port;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.PlatformProperties;
import com.zxf.platform.core.domain.model.Money;
import com.zxf.platform.core.domain.model.Order;
import com.zxf.platform.core.domain.port.PricingPolicy;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 策略注册表：替代 if-else，启动期 fail-fast（文档 5.2.5）。
 *
 * <p>{@code List<PricingPolicy>} 由 Spring 注入容器中全部实现。若配置漂移
 * （{@code platform.entity=alpha} 但装配产物里没有 Alpha 实现——构建 profile 裁剪失效，
 * 或实现漏标 {@code @ForEntity}），容器里实现缺失——
 * 构造器内的校验让应用在<b>启动期立即失败</b>，而不是运行期第一次计价时才炸
 * （{@code @ForEntity} 激活只读 {@code platform.entity}，与 {@code SPRING_PROFILES_ACTIVE}
 * 无直接关系——后者仅经 {@code application-{entity}.yaml} 间接提供该属性）。
 *
 * <p>对外只暴露意图方法 {@link #priceFor(Order)}（Tell, Don't Ask）：调用方不再
 * 先取策略再调用，计价的两步协作封装在注册表内（文档 5.9 规则 4）。
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
                                    "实体 %s 装配了多个 PricingPolicy（%s / %s），同一实体必须有且仅有一个实现，请检查 @ForEntity 限定"
                                            .formatted(first.supports(),
                                                    first.getClass().getName(), second.getClass().getName()));
                        }));
        // 启动期防护：当前实体必须有且仅有一个实现装配，否则直接启动失败
        if (!pricingPolicies.containsKey(properties.entity())) {
            throw new IllegalStateException(
                    "当前实体 %s 未装配 PricingPolicy，请检查构建产物（Maven profile）与 platform.entity 是否一致，"
                            + "以及扩展点实现是否标注了 @ForEntity(%s)"
                            .formatted(properties.entity(), properties.entity()));
        }
    }

    /** 按当前实体上下文为订单计价（未装配时 fail-loud）。 */
    public Money priceFor(Order order) {
        Assert.notNull(order, "订单不能为空");
        var entity = EntityContext.current();
        var policy = pricingPolicies.get(entity);
        // 部署级模型下不会走到（Filter 只设置为 platform.entity，构造器已校验）；
        // 混部演进（从 Header/Token 解析实体）后这是真实场景，当场 fail-loud 而非延迟 NPE
        if (policy == null) {
            throw new IllegalStateException(
                    "运行时实体 %s 未装配 PricingPolicy（已装配: %s），请检查实体上下文来源与装配是否一致"
                            .formatted(entity, pricingPolicies.keySet()));
        }
        return policy.calculate(order);
    }

    /** 装配冒烟专用（文档 5.2.5）：包私有，仅限同包的装配测试断言。 */
    boolean hasPolicy(EntityType entity) {
        return pricingPolicies.containsKey(entity);
    }
}
