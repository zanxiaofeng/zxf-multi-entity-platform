package com.zxf.platform.core.infrastructure.persistence;

import com.zxf.platform.core.domain.model.Money;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.math.BigDecimal;
import java.util.Currency;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * {@link Money} 的单列持久化（文档 5.9 JPA 注记）：record 不能作可移植
 * {@code @Embeddable}（规范要求无参构造 + 可变字段），改用 autoApply converter
 * 落 {@code "<amount> <currency>"} 单列（如 {@code "226.00 CNY"}）——领域层保持
 * 不可变 record，映射细节留在基础设施层。
 */
@Converter(autoApply = true)
public class MoneyConverter implements AttributeConverter<Money, String> {

    private static final char SEPARATOR = ' ';

    @Override
    public String convertToDatabaseColumn(@Nullable Money attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.amount().toPlainString() + SEPARATOR + attribute.currency().getCurrencyCode();
    }

    @Override
    public Money convertToEntityAttribute(@Nullable String dbData) {
        if (dbData == null) {
            return null;
        }
        int separator = dbData.lastIndexOf(SEPARATOR);
        Assert.isTrue(separator > 0, () -> "Money 列格式应为 '<amount> <currency>': " + dbData);
        return new Money(new BigDecimal(dbData.substring(0, separator)),
                Currency.getInstance(dbData.substring(separator + 1)));
    }
}
