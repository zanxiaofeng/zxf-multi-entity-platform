package com.zxf.platform.core.application;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * Schema 驱动的校验规则绑定（文档 5.8.3）：规则存配置（per-entity yaml），
 * core 只写解释器——改规则不发版。
 *
 * <p>关键纪律：Schema 只能表达<b>声明式约束</b>。一旦规则需要分支逻辑
 * （"金额大于 X 且币种为 Y 时……"），立即升级为扩展点——禁止在 Schema 里
 * 发明 DSL 表达式语言（与 2.2 禁止的最差形态同源）。
 */
@ConfigurationProperties(prefix = "platform.validation")
public record PlatformValidationProperties(List<Rule> rules) {

    public PlatformValidationProperties {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** 单条声明式规则：{@code field} 指定约束目标，{@code max}/{@code in} 按字段语义取用。 */
    public record Rule(String field, Long max, List<String> in) {

        public Rule {
            Assert.hasText(field, "校验规则必须声明 field");
        }
    }
}
