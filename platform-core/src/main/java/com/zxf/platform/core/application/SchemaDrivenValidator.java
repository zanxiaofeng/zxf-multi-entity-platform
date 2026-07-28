package com.zxf.platform.core.application;

import com.zxf.platform.core.application.PlatformValidationProperties.Rule;
import com.zxf.platform.core.domain.model.Money;
import com.zxf.platform.core.domain.model.Order;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * Schema 驱动校验解释器（文档 5.8.3）：无实体判断，规则全来自配置。
 *
 * <p>违反约束抛 {@link IllegalArgumentException}（客户端可修正的输入问题，
 * 经 {@code RestExceptionHandler} 映射为 400）；规则形态不认识抛
 * {@link IllegalStateException}（配置缺陷，属编程错误）。
 */
@Component
@RequiredArgsConstructor
public class SchemaDrivenValidator {

    private final PlatformValidationProperties properties;

    /**
     * 按当前实体配置的规则逐条校验订单。
     *
     * @param order 待校验订单（必须先完成计价，金额/币种规则以计价结果为目标）
     * @throws RuleViolationException 违反配置约束（客户端可修正，映射 400）
     * @throws IllegalStateException 规则形态不认识或未计价即校验（配置/编排缺陷，属编程错误）
     */
    public void validate(Order order) {
        Assert.notNull(order, "order 不能为空");
        properties.rules().forEach(rule -> apply(rule, order));
    }

    private void apply(Rule rule, Order order) {
        switch (rule.field()) {
            case "amount" -> checkMax(rule, order);
            case "currency" -> checkIn(rule, order);
            default -> throw new IllegalStateException("未知的校验规则字段: " + rule.field());
        }
    }

    private void checkMax(Rule rule, Order order) {
        Assert.state(rule.max() != null, "amount 规则必须配置 max");
        var amount = pricedAmount(order);
        if (amount.compareTo(BigDecimal.valueOf(rule.max())) > 0) {
            throw new RuleViolationException(
                    "订单金额 %s 超过配置上限 %d".formatted(amount.toPlainString(), rule.max()));
        }
    }

    private void checkIn(Rule rule, Order order) {
        Assert.state(rule.in() != null && !rule.in().isEmpty(), "currency 规则必须配置 in");
        var currency = pricedMoney(order).currency().getCurrencyCode();
        if (!rule.in().contains(currency)) {
            throw new RuleViolationException("币种 %s 不在允许范围 %s".formatted(currency, rule.in()));
        }
    }

    /** 校验以计价结果为目标——管道在定价之后运行（编排顺序见应用服务）。 */
    private BigDecimal pricedAmount(Order order) {
        return pricedMoney(order).amount();
    }

    private Money pricedMoney(Order order) {
        var price = order.price();
        Assert.state(price != null, "校验计价字段前必须先完成计价");
        return price;
    }
}
