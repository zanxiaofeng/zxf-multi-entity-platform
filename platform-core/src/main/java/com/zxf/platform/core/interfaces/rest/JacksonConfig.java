package com.zxf.platform.core.interfaces.rest;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;

/**
 * 对外 JSON 序列化契约（金额 plain 形态）。
 *
 * <p><b>为什么需要本类</b>：金额经 {@code Money} 构造期归一化（{@code stripTrailingZeros}
 * + 负 scale 归零）后 scale 恒 ≥ 0，Jackson 序列化理论上不再出现科学计数法；本 feature
 * 作为<b>纵深防御</b>保留——一旦归一化逻辑回退（如手滑移除 {@code setScale(0)} 兜底），
 * {@code 190.00 → 1.9E+2} 会把科学计数法写进对外 JSON，此处保证恒为可读十进制形态
 * （{@code 190} / {@code 226}），与 README 契约示例一致（两侧 e2e 下单用例的
 * {@code doesNotContain("E+")} 断言守护本配置不回退）。
 *
 * <p><b>为什么不用 yaml 配置</b>：Boot 3 的 {@code spring.jackson.generator.*} 属性绑定
 * {@code JsonGenerator.Feature}，Jackson 3 将 stream feature 移至独立的
 * {@link StreamWriteFeature} 枚举后 Boot 4 已<b>移除该属性组</b>——写进 yaml 会被静默忽略。
 * 程序式 customizer 是 Boot 4 下的标准入口。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer bigDecimalAsPlainCustomizer() {
        return builder -> builder.enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN);
    }
}
