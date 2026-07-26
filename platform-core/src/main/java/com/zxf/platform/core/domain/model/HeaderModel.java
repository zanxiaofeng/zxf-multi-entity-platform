package com.zxf.platform.core.domain.model;

import org.springframework.util.Assert;

/** 页眉模型：模板方法的差异点返回值，渲染逻辑留在骨架（文档 5.8.2）。 */
public record HeaderModel(String title) {

    public HeaderModel {
        Assert.hasText(title, "页眉标题不能为空");
    }
}
