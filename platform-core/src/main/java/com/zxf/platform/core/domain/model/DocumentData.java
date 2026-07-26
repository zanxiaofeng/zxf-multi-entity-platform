package com.zxf.platform.core.domain.model;

import org.springframework.util.Assert;

/** 文档内容：模板方法的通用载荷（文档 5.8.2）。 */
public record DocumentData(String title, String content) {

    public DocumentData {
        Assert.hasText(title, "文档标题不能为空");
        Assert.notNull(content, "文档内容不能为空");
    }
}
