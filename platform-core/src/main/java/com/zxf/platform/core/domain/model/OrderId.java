package com.zxf.platform.core.domain.model;

import org.springframework.util.Assert;

/**
 * 订单标识值对象（文档 5.9 军规 3）：编译期杜绝"把 entity 字符串传进 orderId 参数"。
 *
 * <p>内部以字符串承载（流程变量、REST 路径、JSON 均为字符串形态）；由数据库
 * IDENTITY 主键经 {@link #of(long)} 包装而来——主键生成仍是持久化职责。
 */
public record OrderId(String value) {

    public OrderId {
        Assert.hasText(value, "OrderId 不能为空");
    }

    /** 由数据库主键包装。 */
    public static OrderId of(long id) {
        return new OrderId(String.valueOf(id));
    }
}
