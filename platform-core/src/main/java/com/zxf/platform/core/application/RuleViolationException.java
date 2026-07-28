package com.zxf.platform.core.application;

/**
 * Schema 驱动校验规则违反（文档 5.8.3）：订单违反了 per-entity 配置的声明式约束，
 * 属<b>客户端可修正的输入问题</b>，经 {@code RestExceptionHandler} 映射为 400。
 *
 * <p><b>为何不复用 {@code IllegalArgumentException}</b>：项目中 {@code Assert.notNull} /
 * {@code Assert.hasText} 等契约校验同样抛 {@code IllegalArgumentException}，但其语义是
 * <b>编程错误</b>（exception-handling §2：应映射 500 兜底）。两类异常类型相同、语义不同，
 * 统一映射到 400 会让编程错误被误报为客户端问题。本异常将「可修正的输入违反配置规则」
 * 从 {@code IllegalArgumentException} 中拆出，让全局 handler 能精确按语义分流。
 */
public class RuleViolationException extends RuntimeException {

    public RuleViolationException(String message) {
        super(message);
    }
}
