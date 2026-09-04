package com.zxf.platform.core.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Schema 驱动的校验规则绑定（文档 5.8.3）：规则存配置（per-entity yaml），
 * core 只写解释器——改规则不发版。
 *
 * <p>关键纪律：Schema 只能表达<b>声明式约束</b>。一旦规则需要分支逻辑
 * （"金额大于 X 且币种为 Y 时……"），立即升级为扩展点——禁止在 Schema 里
 * 发明 DSL 表达式语言（与 2.2 禁止的最差形态同源）。
 *
 * <p>校验走 {@code @Validated} 声明式注解（validation.md §1.1 / §2.8）：
 * 规则缺失 {@code field} 时绑定后启动即失败；{@code List<@Valid Rule>} 级联
 * 校验列表元素。compact constructor 仅保留 null 防御性默认值（非校验）。
 */
@Validated
@ConfigurationProperties(prefix = "platform.validation")
public record PlatformValidationProperties(List<@Valid Rule> rules) {

    public PlatformValidationProperties {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** 单条声明式规则：{@code field} 指定约束目标，{@code max}/{@code in} 按字段语义取用。 */
    public record Rule(@NotBlank(message = "校验规则必须声明 field") String field, Long max, List<String> in) {

        /**
         * 绑定期交叉校验（评审修复 M5）：{@code amount} 规则缺 {@code max}、{@code currency}
         * 规则缺 {@code in} 属配置缺陷——此前在第一个请求到达时才由解释器 {@code Assert.state}
         * 抛出，与全项目"启动期 fail-fast"哲学不符。提前到绑定校验期；解释器侧断言保留为纵深防御。
         */
        @AssertTrue(message = "field=amount 的校验规则必须配置 max")
        boolean isMaxConfiguredForAmount() {
            return !"amount".equals(field) || max != null;
        }

        /** 同上：{@code currency} 规则的 {@code in} 必须是非空列表。 */
        @AssertTrue(message = "field=currency 的校验规则必须配置非空 in 列表")
        boolean isInConfiguredForCurrency() {
            return !"currency".equals(field) || (in != null && !in.isEmpty());
        }
    }
}
