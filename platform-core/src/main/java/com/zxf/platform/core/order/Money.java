package com.zxf.platform.core.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;

/**
 * 金额（值对象）。record 形态的 JPA Embeddable，由 Hibernate 直接支持。
 */
@Embeddable
public record Money(
        @Column(precision = 19, scale = 2) BigDecimal amount,
        @Column(length = 3) String currency) {

    public static Money cny(BigDecimal amount) {
        return new Money(amount, "CNY");
    }
}
