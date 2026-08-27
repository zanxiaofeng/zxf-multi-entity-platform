package com.zxf.platform.core.interfaces.rest;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamWriteFeature;

/**
 * 对外 JSON 序列化契约（金额 plain 形态）。
 *
 * <p><b>为什么需要本类</b>：金额经 {@code Money} 构造期归一化（{@code stripTrailingZeros}）
 * 后 scale 可为负（如 {@code 190.00 → 1.9E+2}），Jackson 默认按 {@code toString} 序列化会把
 * <b>科学计数法写进对外 JSON</b>。开启 {@link StreamWriteFeature#WRITE_BIGDECIMAL_AS_PLAIN}
 * 后金额恒为可读十进制形态（{@code 190} / {@code 226}），与 README 契约示例一致
 * （两侧 e2e 下单用例的 {@code doesNotContain("E+")} 断言守护本配置不回退）。
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
