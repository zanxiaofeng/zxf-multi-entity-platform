package com.zxf.platform.core.context;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * {@link ForEntity} 的条件求值器（文档 5.10.1）。
 *
 * <p>以 {@code platform.entity} 为唯一开关源：注解属性即 {@link EntityType}（与 {@code supports()}
 * 同源，消除字符串漂移），实际值取自 environment。两者一致即 match，否则 noMatch——
 * {@code platform.entity} 未配置时返回 noMatch，由 {@code PlatformProperties} 启动校验兜底报错
 * （文档 5.2.2：{@code @Validated} + {@code @NotNull}，validation.md §2.8）。
 *
 * <p>等价语义可由裸 {@code @Profile("alpha")} + 部署清单 {@code SPRING_PROFILES_ACTIVE=alpha} 表达，
 * 但那构成两套开关源（profile 与 {@code platform.entity}），漂移即事故（文档 5.3 唯一事实来源规约）。
 * 本类把"激活"收敛到 {@code platform.entity} 单一事实源——6.3 中 {@code SPRING_PROFILES_ACTIVE}
 * 的一致性要求随之取消（profile 仅保留环境用途）。
 */
public class EntityCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // 防御误用：本 Condition 仅服务于 @ForEntity 复合注解，禁止直接 @Conditional(EntityCondition.class)
        // 绕过注解——否则下面 getEnum 会因 missing annotation 抛 NPE，错误信息毫无指向性
        var annotation = metadata.getAnnotations().get(ForEntity.class);
        if (!annotation.isPresent()) {
            return ConditionOutcome.noMatch(
                    "EntityCondition 仅服务于 @ForEntity 复合注解，请改用 @ForEntity 而非直接 @Conditional(EntityCondition.class)");
        }
        var expected = annotation.getEnum("value", EntityType.class);

        // 走 Binder.bind 而非 getProperty + 字符串比对：与 PlatformProperties 的 @ConfigurationProperties
        // 走同一套 relaxed binding（含大小写、连字符容忍），消除两条解析路径的语义漂移（5.3 唯一事实来源）
        var actual = Binder.get(context.getEnvironment())
                .bind("platform.entity", EntityType.class)
                .orElse(null);
        if (actual == null) {
            return ConditionOutcome.noMatch("platform.entity 未配置或无法解析为 EntityType"
                    + "（由 PlatformProperties 启动校验兜底报错）");
        }
        return expected == actual
                ? ConditionOutcome.match("entity=" + actual)
                : ConditionOutcome.noMatch("期望 entity=" + expected + "，实际=" + actual);
    }
}
