package com.zxf.platform.alpha.adapter;

import com.zxf.platform.core.domain.model.FooterModel;
import com.zxf.platform.core.domain.model.HeaderModel;
import com.zxf.platform.core.domain.service.AbstractDocumentGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Alpha 单据生成（文档 5.8.2 模板方法）：只提供页眉/页脚两个差异点，
 * 骨架与正文渲染在 core——差异点超过两个或会增长时，退化为策略/管道。
 */
@Component
@Profile("alpha")
public class AlphaDocumentGenerator extends AbstractDocumentGenerator {

    @Override
    protected HeaderModel header() {
        return new HeaderModel("Alpha 集团 · 订单凭证");
    }

    @Override
    protected FooterModel footer() {
        return new FooterModel("Alpha 财税合规存根 · 价税合计含 13% 增值税");
    }
}
