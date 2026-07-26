package com.zxf.platform.beta.adapter;

import com.zxf.platform.core.domain.model.FooterModel;
import com.zxf.platform.core.domain.model.HeaderModel;
import com.zxf.platform.core.domain.service.AbstractDocumentGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Beta 单据生成（文档 5.8.2 模板方法）：与 Alpha 同骨架、不同页眉/页脚——
 * 差异点少且稳定时模板方法最省，这是它的适用边界。
 */
@Component
@Profile("beta")
public class BetaDocumentGenerator extends AbstractDocumentGenerator {

    @Override
    protected HeaderModel header() {
        return new HeaderModel("Beta 公司 · 订单回执");
    }

    @Override
    protected FooterModel footer() {
        return new FooterModel("Beta 客户留存联 · 95 折后价格");
    }
}
