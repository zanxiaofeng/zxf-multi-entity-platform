package com.zxf.platform.core.domain.service;

import com.zxf.platform.core.domain.model.DocumentData;
import com.zxf.platform.core.domain.model.FooterModel;
import com.zxf.platform.core.domain.model.HeaderModel;
import java.nio.charset.StandardCharsets;
import org.springframework.util.Assert;

/**
 * 模板方法的边界示范（文档 5.8.2）。
 *
 * <p><b>适用边界：差异点 ≤ 2 且永远不会增长。</b>
 * 一旦差异点会增长（新实体要改中间步骤），必须退化为策略/管道——
 * 继承体系的扩展成本随差异点数量线性恶化。
 *
 * <p>骨架固定（{@code final}）：页眉/页脚是差异点（实体实现），正文渲染通用。
 * 差异点只返回领域内容（{@link HeaderModel} / {@link FooterModel}），渲染逻辑留在骨架。
 */
public abstract class AbstractDocumentGenerator {

    /** 固定骨架：页眉 → 正文 → 页脚，顺序不可被子类改变。 */
    public final byte[] generate(DocumentData data) {
        Assert.notNull(data, "data 不能为空");
        var doc = new StringBuilder();
        renderHeader(doc, header());
        renderBody(doc, data);
        renderFooter(doc, footer());
        return toBytes(doc);
    }

    /** 差异点：实体页眉。 */
    protected abstract HeaderModel header();

    /** 差异点：实体页脚。 */
    protected abstract FooterModel footer();

    private void renderHeader(StringBuilder doc, HeaderModel header) {
        doc.append("== ").append(header.title()).append(" ==\n");
    }

    private void renderBody(StringBuilder doc, DocumentData data) {
        doc.append(data.title()).append('\n').append(data.content()).append('\n');
    }

    private void renderFooter(StringBuilder doc, FooterModel footer) {
        doc.append("-- ").append(footer.note()).append(" --\n");
    }

    private byte[] toBytes(StringBuilder doc) {
        return doc.toString().getBytes(StandardCharsets.UTF_8);
    }
}
