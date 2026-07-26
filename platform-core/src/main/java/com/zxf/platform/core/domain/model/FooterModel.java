package com.zxf.platform.core.domain.model;

import org.springframework.util.Assert;

/** 页脚模型：模板方法的差异点返回值，渲染逻辑留在骨架（文档 5.8.2）。 */
public record FooterModel(String note) {

    public FooterModel {
        Assert.hasText(note, "页脚备注不能为空");
    }
}
