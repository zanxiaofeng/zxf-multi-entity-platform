package com.zxf.platform.core.interfaces.rest;

import com.zxf.platform.core.application.RuleViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 异常映射（文档 5.1.1 interfaces.rest + 7.7.2 组件 10）：统一 RFC 9457 ProblemDetail 响应。
 *
 * <p>工程未引入 {@code ApiResponse} 信封（CLAUDE.md「骨架适用范围」：ApiResponse 规范
 * 面向后续新增业务模块），直接走 Spring Boot 内置的 {@link ProblemDetail}（{@code application/problem+json}）。
 * 与 {@code .claude/rules/exception-handling.md §6.2 矩阵} 对齐——覆盖 demo 所需的关键映射，
 * {@code BusinessException} / {@code ErrorCode} 体系待业务模块引入时补全。
 *
 * <p>4xx 记 WARN（客户端问题）；兜底 500 记 ERROR + 完整堆栈。
 */
@Slf4j
@RestControllerAdvice
public class RestExceptionHandler {

    /** Schema 驱动校验规则违反（文档 5.8.3）：客户端可修正的输入问题 → 400。
     *  <p>与 {@code IllegalArgumentException}（{@code Assert} 契约违反，属编程错误）区分：
     *  后者无独立 handler，落兜底 {@link #handleUnexpected} → 500（exception-handling §2）。 */
    @ExceptionHandler(RuleViolationException.class)
    public ProblemDetail handleRuleViolation(RuleViolationException ex) {
        log.warn("校验规则违反: {}", ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("请求参数不合法");
        return problem;
    }

    /** {@code @RequestBody @Valid} 校验失败（{@code @NotBlank} / {@code @Positive} 等）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        log.warn("请求体校验失败: {}", ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求体校验失败");
        problem.setTitle("请求参数不合法");
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> "%s: %s（当前值: %s）".formatted(fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue()))
                .toList();
        problem.setProperty("errors", fieldErrors);
        return problem;
    }

    /**
     * {@code @PathVariable} / {@code @RequestParam} 校验失败
     * （SF 6.1+ 方法校验，类级无 {@code @Validated} 走内建方法校验，抛此异常）。
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleMethodValidation(HandlerMethodValidationException ex) {
        log.warn("方法参数校验失败: {}", ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "方法参数校验失败");
        problem.setTitle("请求参数不合法");
        return problem;
    }

    /** JSON 语法错误 / body 缺失 / 枚举值非法。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求体格式不合法或缺失");
    }

    /** SF 6.1+：无匹配路由（取代旧的 NoHandlerFoundException 配置开关）。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("资源不存在: {}", ex.getResourcePath());
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "请求的资源不存在");
    }

    /**
     * {@link ResponseStatusException}（Controller 主动抛出的状态码异常，如
     * {@code OrderController.get} 找不到订单时抛 404）：透传状态码到 ProblemDetail，
     * 不走兜底 handler（否则 404 会被吞成 500）。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
        log.warn("ResponseStatus 异常: {} {}", ex.getStatusCode(), ex.getReason());
        return ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
    }

    /** 兜底：未预期的异常 → 500，固定文案不回显内部细节，ERROR + 完整堆栈。 */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("未预期的异常", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
    }
}
