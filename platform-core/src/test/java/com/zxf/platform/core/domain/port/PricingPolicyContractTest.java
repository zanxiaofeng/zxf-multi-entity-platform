package com.zxf.platform.core.domain.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.ForEntity;
import org.junit.jupiter.api.Test;

/**
 * 扩展点契约测试基类（文档 8.4）。
 *
 * <p>core 提供抽象基类，实体模块继承即获得契约回归，新增实体契约测试零编写——
 * 这是"内核可演进"在测试侧的闭环（文档 2.6）。
 *
 * <p>当前覆盖两条最关键的契约（防 {@code @ForEntity} 与 {@code supports()} 漂移）：
 * <ul>
 *   <li>{@code supports()} 返回值与子类声明的 {@code expectedEntity()} 一致；</li>
 *   <li>{@code @ForEntity} 注解的 {@code value()} 与 {@code supports()} 一致
 *       （文档 5.10.1：注解值与 supports 收敛为同一份编译期事实）。</li>
 * </ul>
 *
 * <p>通用计算契约（非负、空扩展属性容忍等）依赖测试夹具（{@code Orders.minimalValid()}
 * 等），待 fixture 类补齐后再扩展——本基类只承担"所有实现都必须满足"的零依赖契约。
 *
 * <p>跨模块继承机制：本类在 platform-core test 源集，由 {@code maven-jar-plugin} 的
 * {@code test-jar} goal 发布，实体模块以 {@code <type>test-jar</type>} 引用（pom 已配置）。
 */
public abstract class PricingPolicyContractTest {

    /** 由实体模块提供被测实现。 */
    protected abstract PricingPolicy policy();

    /** 由实体模块声明自身适配的实体。 */
    protected abstract EntityType expectedEntity();

    @Test
    void supports与所在模块实体一致() {
        assertThat(policy().supports()).isEqualTo(expectedEntity());
    }

    @Test
    void 激活注解声明的实体与supports一致() {
        // @ForEntity 的枚举值与 supports() 是同一份事实（5.10.1），防两处漂移——
        // ArchUnit 只能守护"必须标注 @ForEntity"（beMetaAnnotatedWith(Conditional.class)），
        // 标注后的 value 与 supports() 一致性靠本契约测试兜底
        var annotation = policy().getClass().getAnnotation(ForEntity.class);
        assertThat(annotation)
                .as("@ForEntity 必须标注（ArchUnit 已守护），且 value 与 supports() 一致")
                .isNotNull();
        assertThat(annotation.value()).isEqualTo(policy().supports());
    }
}
