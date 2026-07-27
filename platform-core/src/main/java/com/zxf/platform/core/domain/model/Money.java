package com.zxf.platform.core.domain.model;

import java.math.BigDecimal;
import java.util.Currency;
import org.springframework.util.Assert;

/**
 * 金额值对象（文档 5.9 军规 3）：金额与币种不可分，校验收聚在紧凑构造器，
 * 消除散落各处的 {@code if (amount < 0)}。
 *
 * <p>持久化：record 不能作可移植 {@code @Embeddable}，经
 * {@code infrastructure.persistence.MoneyConverter}（autoApply）落单列，映射细节不出基础设施层。
 */
public record Money(BigDecimal amount, Currency currency) {

    private static final Currency CNY = Currency.getInstance("CNY");

    public Money {
        Assert.notNull(amount, "金额不能为空");
        Assert.notNull(currency, "币种不能为空");
        // effectively final 副本供 lambda 引用（compact constructor 内 amount 后续会被重新赋值）
        var original = amount;
        Assert.isTrue(original.signum() >= 0, () -> "金额不能为负: " + original);
        // BigDecimal.equals 对 scale 敏感（2.00 ≠ 2.0），record 的 equals/hashCode 委托它——
        // 构造期归一化 trailing 零，保证相等性语义与 compareTo 一致（文档 5.9 军规 3 落地）
        // signum==0 特判：JDK 19+ 的 stripTrailingZeros 对 0 已返回 scale=0，
        // 但保留特判让"JDK 行为变化/未来切换 BigDecimal.ZERO 常量"零回归
        amount = original.signum() == 0 ? BigDecimal.ZERO : original.stripTrailingZeros();
    }

    /** demo 便捷工厂：人民币金额。 */
    public static Money cny(String amount) {
        return cny(new BigDecimal(amount));
    }

    /** demo 便捷工厂：人民币金额（计价结果已是 {@link BigDecimal} 时使用）。 */
    public static Money cny(BigDecimal amount) {
        return new Money(amount, CNY);
    }

    /** 单价 × 数量 = 总价（行为放在值对象上，军规 9「Tell, Don't Ask」）。 */
    public Money times(int multiplier) {
        Assert.isTrue(multiplier > 0, () -> "乘数必须为正数: " + multiplier);
        return new Money(amount.multiply(BigDecimal.valueOf(multiplier)), currency);
    }
}
