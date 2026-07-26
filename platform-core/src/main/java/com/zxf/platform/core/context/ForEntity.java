package com.zxf.platform.core.context;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * 实体激活复合注解（文档 5.10.1 已落地）。
 *
 * <p>扩展点实现类用 {@code @ForEntity(EntityType.ALPHA)} 替代裸 {@code @Profile("alpha")}——
 * 注解值与 {@code supports()} 返回值、{@link EntityType} 枚举收敛为<b>同一份编译期事实</b>
 * （裸字符串会与枚举、{@code supports()} 构成三份事实，改名/新增实体时三处易漂移）。
 *
 * <p>底层以 {@code platform.entity} 为唯一开关源：激活逻辑由 {@link EntityCondition} 解析，
 * 失败信息、{@code supports()} 校验（5.2.5 不变，仍是启动期兜底）全部收口到一处。
 *
 * <p>ArchUnit 守护：扩展点实现类必须被本注解（元注解 {@link Conditional}）限定
 * （{@code beMetaAnnotatedWith(Conditional.class)}，文档 8.3）。
 *
 * @see EntityCondition
 * @see EntityType
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(EntityCondition.class)
public @interface ForEntity {

    /** 声明本实现适配的实体——必须与 {@code supports()} 返回值一致（契约测试守护，文档 8.4）。 */
    EntityType value();
}
