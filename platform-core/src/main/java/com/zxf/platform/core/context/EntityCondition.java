package com.zxf.platform.core.context;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@link ForEntity} 的条件求值器（文档 5.10.1）。
 *
 * <p>以 {@code platform.entity} 为唯一开关源：注解属性即 {@link EntityType}（与 {@code supports()}
 * 同源，消除字符串漂移），实际值取自 environment。两者一致即 match，否则 noMatch——
 * {@code platform.entity} 未配置时返回 noMatch，由 {@code PlatformProperties} 启动校验兜底报错
 * （文档 5.2.2：{@code Assert.notNull(entity, "platform.entity 必须配置")}）。
 *
 * <p>等价语义可由裸 {@code @Profile("alpha")} + 部署清单 {@code SPRING_PROFILES_ACTIVE=alpha} 表达，
 * 但那构成两套开关源（profile 与 {@code platform.entity}），漂移即事故（文档 5.3 唯一事实来源规约）。
 * 本类把"激活"收敛到 {@code platform.entity} 单一事实源——6.3 中 {@code SPRING_PROFILES_ACTIVE}
 * 的一致性要求随之取消（profile 仅保留环境用途）。
 */
public class EntityCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // getEnum 直接取枚举值：注解属性即 EntityType，与 supports() 同源，消除字符串漂移
        var expected = metadata.getAnnotations().get(ForEntity.class).getEnum("value", EntityType.class);
        var actual = context.getEnvironment().getProperty("platform.entity");
        if (actual == null) {
            return ConditionOutcome.noMatch("platform.entity 未配置（由 PlatformProperties 启动校验兜底报错）");
        }
        return expected.name().equalsIgnoreCase(actual)
                ? ConditionOutcome.match("entity=" + actual)
                : ConditionOutcome.noMatch("期望 entity=" + expected + "，实际=" + actual);
    }
}
