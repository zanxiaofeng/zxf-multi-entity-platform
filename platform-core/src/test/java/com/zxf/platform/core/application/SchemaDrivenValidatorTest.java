package com.zxf.platform.core.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zxf.platform.core.application.PlatformValidationProperties.Rule;
import com.zxf.platform.core.domain.model.Money;
import com.zxf.platform.core.domain.model.Order;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 文档 5.8.3：Schema 驱动校验——规则来自配置，解释器零实体判断。 */
class SchemaDrivenValidatorTest {

    @Test
    void 规则全部满足时通过() {
        var validator = validatorWith(
                new Rule("amount", 100000L, null),
                new Rule("currency", null, List.of("CNY", "USD")));

        assertThatCode(() -> validator.validate(pricedOrder("99999.00")))
                .doesNotThrowAnyException();
    }

    @Test
    void 金额超上限时拒绝且消息含边界与实际值() {
        var validator = validatorWith(new Rule("amount", 100000L, null));

        assertThatThrownBy(() -> validator.validate(pricedOrder("100000.01")))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("100000");
    }

    @Test
    void 金额等于上限时通过() {
        var validator = validatorWith(new Rule("amount", 100000L, null));

        assertThatCode(() -> validator.validate(pricedOrder("100000.00")))
                .doesNotThrowAnyException();
    }

    @Test
    void 币种不在允许范围时拒绝() {
        var validator = validatorWith(new Rule("currency", null, List.of("USD")));

        assertThatThrownBy(() -> validator.validate(pricedOrder("1.00")))
                .isInstanceOf(RuleViolationException.class)
                .hasMessageContaining("CNY");
    }

    @Test
    void 未计价订单校验金额显式失败() {
        var validator = validatorWith(new Rule("amount", 100000L, null));

        assertThatThrownBy(() -> validator.validate(Order.from("widget", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("计价");
    }

    @Test
    void 未计价订单校验币种显式失败() {
        var validator = validatorWith(new Rule("currency", null, List.of("CNY")));

        assertThatThrownBy(() -> validator.validate(Order.from("widget", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("计价");
    }

    @Test
    void 未知规则字段属配置缺陷() {
        var validator = validatorWith(new Rule("region", null, List.of("CN")));

        assertThatThrownBy(() -> validator.validate(pricedOrder("1.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("region");
    }

    private static SchemaDrivenValidator validatorWith(Rule... rules) {
        return new SchemaDrivenValidator(new PlatformValidationProperties(List.of(rules)));
    }

    private static Order pricedOrder(String price) {
        var order = Order.from("widget", 1);
        order.priceTo(Money.cny(price));
        return order;
    }
}
