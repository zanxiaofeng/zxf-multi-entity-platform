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

    @Test
    void 整十金额归一化无负scale() {
        // 190.00 的 unscaled 尾部零多于 scale：stripTrailingZeros 产生 1.9E+2（scale=-1）——
        // BigDecimal.equals 对 scale 敏感，1.9E+2 与 190 不等（compareTo 却相等）。
        // 真实触发路径：Beta 计价 200 × 0.95 = 190.00，落库 toPlainString 为 "190"，
        // JPA 读回 scale=0——同一笔订单内存态与读回态 equals 不等，进 HashSet/HashMap 即重复
        var money = Money.cny("190.00");
        assertThat(money.amount().scale()).isNotNegative();
        assertThat(money.amount().toString()).isEqualTo("190");
    }

    @Test
    void 尾零跨数量级金额与等值简写相等() {
        // 等价类：unscaled 尾部零多于 scale 的全部形态（190.00 / 1130.00 / 2200.0）
        assertThat(Money.cny("190.00")).isEqualTo(Money.cny("190"));
        assertThat(Money.cny("1130.00")).isEqualTo(Money.cny("1130"));
        assertThat(Money.cny("2200.0")).isEqualTo(Money.cny("2200"));
        // compareTo 与 equals 语义一致（构造期归一化的承诺，文档 5.9 军规 3）：
        // equals 相等的两个 Money，其 amount 的 compareTo 也必为零
        assertThat(Money.cny("190.00").amount().compareTo(Money.cny("190").amount())).isZero();
    }

    @Test
    void 计价乘积的等值形态相等() {
        // Beta 真实计价路径：113 × 1 = 113（scale 0）与 226.00 / 2 无关——
        // 200 × 0.95 = 19.000... 乘积 strip 后应为 190（scale 0），与手写 190 相等
        assertThat(Money.cny(new BigDecimal("200").multiply(new BigDecimal("0.95"))))
                .isEqualTo(Money.cny("190"));
    }
}
