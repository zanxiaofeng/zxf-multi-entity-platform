package com.zxf.platform.core.application;

import com.zxf.platform.core.domain.model.OrderContext;
import com.zxf.platform.core.domain.port.OrderStep;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 管道公共首步（文档 5.8.1 + 5.8.3）：Schema 驱动校验。两实体共用语义，
 * 规则数值来自各自实体配置——"约束数值不同"这类浅层差异的归处。
 */
@Component
@Order(1)
public class SchemaValidationStep implements OrderStep {

    private final SchemaDrivenValidator validator;

    public SchemaValidationStep(SchemaDrivenValidator validator) {
        this.validator = validator;
    }

    @Override
    public String name() {
        return "schema-validation";
    }

    @Override
    public void execute(OrderContext ctx) {
        validator.validate(ctx.order());
    }
}
