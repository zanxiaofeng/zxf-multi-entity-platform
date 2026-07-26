package com.zxf.platform.core.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 创建订单命令。record + Bean Validation，Jackson 3 原生支持 record 反序列化（文档 5.0）。
 */
public record CreateOrderCommand(
        @NotBlank String item,
        @Positive int quantity) {
}
