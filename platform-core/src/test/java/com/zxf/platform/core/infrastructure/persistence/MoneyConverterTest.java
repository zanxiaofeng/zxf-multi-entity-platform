package com.zxf.platform.core.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zxf.platform.core.domain.model.Money;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/**
 * {@link MoneyConverter} 落库/读回 round-trip 契约：构造期归一化（含负 scale 归零）后，
 * 同一金额在内存态与持久化读回态必须 {@code equals} 相等——写侧 {@code toPlainString}
 * 与读侧 {@code new BigDecimal(String)} 的 scale 必须稳定，否则同一笔订单的 Money
 * 在落库前后语义分裂（HashSet/HashMap 重复）。
 */
class MoneyConverterTest {

    private final MoneyConverter converter = new MoneyConverter();

    @Test
    void 尾零金额roundTrip保持相等() {
        // 触发负 scale 的形态（190.00 → strip → 1.9E+2）：归一化补 setScale(0) 后
        // 落库 "190 CNY"，读回 scale=0——与内存态 equals 相等
        var original = Money.cny("190.00");
        var restored = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(original));

        assertThat(restored).isEqualTo(original);
        assertThat(restored.amount().scale()).isZero();
    }

    @Test
    void 各scale形态roundTrip保持相等() {
        for (var literal : new String[]{"0", "0.00", "113", "113.50", "226.00", "1130.00"}) {
            var original = Money.cny(literal);
            assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(original)))
                    .as("literal %s round-trip 后应相等", literal)
                    .isEqualTo(original);
        }
    }

    @Test
    void 落库列不含科学计数法() {
        // 写侧恒 toPlainString：与 JacksonConfig 的 WRITE_BIGDECIMAL_AS_PLAIN 同一契约（对外 JSON）
        assertThat(converter.convertToDatabaseColumn(Money.cny("190.00"))).isEqualTo("190 CNY");
        assertThat(converter.convertToDatabaseColumn(Money.cny("226.00"))).isEqualTo("226 CNY");
    }

    @Test
    void 空值双向透传() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void 非法列格式快失败() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("190CNY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Money 列格式");
        assertThatThrownBy(() -> converter.convertToEntityAttribute(" CNY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 非法币种快失败() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("190 XYZ"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 币种往返保持() {
        var usd = new Money(new BigDecimal("5"), Currency.getInstance("USD"));
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(usd)))
                .isEqualTo(usd);
    }
}
