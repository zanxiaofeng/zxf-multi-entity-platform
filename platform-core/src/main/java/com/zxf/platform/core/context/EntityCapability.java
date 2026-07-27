package com.zxf.platform.core.context;

import java.util.Set;

/**
 * 实体能力自描述（文档 5.10.2 已落地）：每个实体模块提供一个能力清单 bean，
 * 把"这个模块为哪个实体、覆盖哪些扩展点、什么版本"变成可编程查询的事实。
 *
 * <p>实现类必须 {@code @ForEntity(EntityType.XXX)} 限定（与扩展点实现同源，由
 * {@code EntityCondition} 按 {@code platform.entity} 激活），core 启动时汇总
 * {@code List<EntityCapability>}，由 {@code EntityInfoContributor} 输出到
 * {@code /actuator/info} 供运行期漂移巡检（文档 6.3 第 3 道防线）。
 *
 * <p><b>当前不实现</b> {@code requiredCoreVersion()}（文档 8.6 SPI 破坏性变更管理，
 * 路线图 P1 推迟——内核/实体同库构建同版本发布时 japicmp 收益有限）。落地
 * "系统性装配校验"也暂不抽 {@code CapabilityRegistry}：当前唯一必需扩展点是
 * {@code PricingPolicy}，{@code PolicyRegistry} 构造器已对其 fail-fast（5.2.5），
 * 复制一份校验逻辑是双份维护；待扩展点 ≥3 个、逐点校验散落多处时再统筹。
 */
public interface EntityCapability {

    /** 本实现适配的实体——必须与 {@code @ForEntity} 的 value 一致（契约测试守护）。 */
    EntityType entity();

    /** 本模块提供的扩展点类型集合（如 {@code PricingPolicy.class}；其他扩展点按工程实际补齐）。 */
    Set<Class<?>> providedPolicies();

    /**
     * 本模块版本——demo 写死字符串（与根 pom {@code project.version} 对齐）。
     * 正式工程可走 {@code getClass().getPackage().getImplementationVersion()} 读 Manifest，
     * 但 SB4 fat jar 的子模块包未必有 Manifest 属性，需评估。
     */
    String moduleVersion();
}
