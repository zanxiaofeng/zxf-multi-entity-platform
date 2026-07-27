package com.zxf.platform.core.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/** 文档 5.9 军规 3：金额值对象的契约（校验收聚在构造器，行为在值对象上）。 */
class MoneyTest {

    @Test
    void cny工厂组装金额与币种() {
        var money = Money.cny("113.00");

        assertThat(money.amount()).isEqualByComparingTo(new BigDecimal("113.00"));
        assertThat(money.currency()).isEqualTo(Currency.getInstance("CNY"));
    }

    @Test
    void 单价乘数量得总价() {
        assertThat(Money.cny("113.00").times(2))
                .isEqualTo(Money.cny("226.00"));
    }

    @Test
    void 负金额违反构造契约() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-0.01"), Currency.getInstance("CNY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为负");
    }

    @Test
    void 缺金额或币种违反构造契约() {
        assertThatThrownBy(() -> new Money(null, Currency.getInstance("CNY")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scale归一化消除trailing零差异() {
        // BigDecimal.equals 对 scale 敏感（1.0 ≠ 1.00）—— compact constructor 归一化后两者相等
        assertThat(Money.cny("1.0")).isEqualTo(Money.cny("1.00"));
        assertThat(Money.cny("113.50")).isEqualTo(Money.cny("113.5"));
        // 整数与全零小数归一化后 scale=0
        assertThat(Money.cny("113").amount().scale()).isZero();
        assertThat(Money.cny("113.00").amount().scale()).isZero();
    }

    @Test
    void 零金额归一化无负scale() {
        // 防 0E- 形态（旧 JDK 的 stripTrailingZeros 对 0 返回负 scale）
        var zero = Money.cny("0.00");
        assertThat(zero).isEqualTo(Money.cny("0"));
        assertThat(zero.amount().scale()).isNotNegative();
        assertThat(zero.amount().toString()).isEqualTo("0");
    }
}
