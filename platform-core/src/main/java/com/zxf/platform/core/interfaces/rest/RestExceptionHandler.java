package com.zxf.platform.core.interfaces.rest;

import com.zxf.platform.core.application.RuleViolationException;
import com.zxf.platform.core.domain.exception.OrderNotFoundException;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

    /** 校验错误回显时对敏感字段脱敏（exception-handling §6.2 / logging 脱敏表）。 */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "secret", "token", "apiKey", "accessToken", "refreshToken");

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
        problem.setProperty("errors", fieldErrors(ex));
        return problem;
    }

    /**
     * 乐观锁并发冲突 → 409（exception-handling §6.2 矩阵：{@code OptimisticLockingFailureException}
     * 映射 CONFLICT）。{@code Order} / {@code OutboxEvent} 均声明 {@code @Version}
     * （db-conventions：并发更新丢失的代码级兜底），并发更新同一行时 Hibernate 抛本异常——
     * 缺此 handler 会落入兜底 {@link #handleUnexpected} 变 500 + ERROR 堆栈，<b>可重试的
     * 并发冲突被监控误报为系统故障</b>。客户端语义：重读数据后重试（4xx 记 WARN 不附堆栈）。
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockingFailure(ObjectOptimisticLockingFailureException ex) {
        log.warn("乐观锁并发冲突: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "数据已被并发修改，请刷新后重试");
    }

    /**
     * {@code @ModelAttribute} 表单绑定校验失败 → 400（exception-handling §6.2 矩阵，与
     * {@code @RequestBody} 的 {@link MethodArgumentNotValidException} 须分别声明；后者是
     * {@link BindException} 子类，Spring 按最具体 handler 匹配，二者互不遮蔽）。
     */
    @ExceptionHandler(BindException.class)
    public ProblemDetail handleBindException(BindException ex) {
        log.warn("参数绑定校验失败: {}", ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "参数绑定校验失败");
        problem.setTitle("请求参数不合法");
        problem.setProperty("errors", ex.getFieldErrors().stream()
                .map(fe -> new FieldErrorDetail(fe.getField(), fe.getDefaultMessage(),
                        maskRejectedValue(fe.getField(), fe.getRejectedValue())))
                .toList());
        return problem;
    }

    /**
     * 唯一键冲突等数据完整性违反 → 409（exception-handling §6.2 / db-conventions 约束三层对齐的兜底）。
     * 固定文案不回显 {@code ex.getMessage()}（可能含约束名/SQL 片段）；实体语义应由 Service 层前置校验给出。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("数据完整性冲突: {}", ex.getMostSpecificCause().getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "数据冲突（如唯一键重复），请检查后重试");
    }

    /**
     * 路径/查询参数类型不匹配（如 id 传了非数字）→ 400。
     * 客户端消息含参数名即可，不回显原始值（exception-handling §6.2）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("参数类型不匹配: {} 期望 {}", ex.getName(), ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName() : "未知");
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "参数 " + ex.getName() + " 类型不合法");
        problem.setTitle("请求参数不合法");
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

    /**
     * HTTP 方法不支持（如 DELETE 打到只有 GET/POST 的路径）→ 405。
     * <p>必须显式声明：advice 已有 {@link #handleUnexpected} 兜底，缺本 handler 时
     * 此类客户端错误会被兜底捕获成 500 + ERROR 堆栈（exception-handling §6.2 矩阵）。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("HTTP 方法不支持: {}", ex.getMethod());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED,
                "请求方法不支持: " + ex.getMethod());
        problem.setTitle("请求方法不被允许");
        return problem;
    }

    /** Content-Type 不支持（如 POST 传 {@code text/plain}）→ 415。同上：兜底会吞掉协议异常，须显式声明。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("Content-Type 不支持: {}", ex.getContentType() != null ? ex.getContentType() : "未知");
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "请求的 Content-Type 不支持");
        problem.setTitle("不支持的媒体类型");
        return problem;
    }

    /** 缺少必填请求参数 → 400。客户端消息含参数名即可，不回显原始值（exception-handling §6.2）。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex) {
        log.warn("缺少必填请求参数: {}", ex.getParameterName());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "缺少必填请求参数: " + ex.getParameterName());
        problem.setTitle("请求参数不合法");
        return problem;
    }

    /** SF 6.1+：无匹配路由（取代旧的 NoHandlerFoundException 配置开关）。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("资源不存在: {}", ex.getResourcePath());
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "请求的资源不存在");
    }

    /**
     * 订单不存在（exception-handling §6.2 矩阵：领域 {@code NotFound} 异常 → 404）。
     * {@code code} 属性暴露 {@link OrderNotFoundException#CODE} 稳定契约（e2e 断言守护）。
     */
    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
        log.warn("订单不存在 orderId={}", ex.getOrderId());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("资源不存在");
        problem.setProperty("code", OrderNotFoundException.CODE);
        return problem;
    }

    /** 兜底：未预期的异常 → 500，固定文案不回显内部细节，ERROR + 完整堆栈。 */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("未预期的异常", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
    }

    /** 敏感字段的回显值脱敏为 {@code "***"}，其余原样返回。 */
    private static Object maskRejectedValue(String field, Object rejectedValue) {
        if (SENSITIVE_FIELDS.contains(field)) {
            return "***";
        }
        return rejectedValue;
    }

    /** 字段级校验明细 → 结构化对象数组（api-conventions errors[] 形态：field / message / rejectedValue）。 */
    private static List<FieldErrorDetail> fieldErrors(MethodArgumentNotValidException ex) {
        return ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldErrorDetail(fe.getField(), fe.getDefaultMessage(),
                        maskRejectedValue(fe.getField(), fe.getRejectedValue())))
                .toList();
    }

    /**
     * 字段级校验明细条目（api-conventions Error Response 的 {@code errors[]} 结构：
     * {@code {field, message, rejectedValue}}，敏感字段回显已脱敏）——结构化对象优于
     * 拼接字符串，客户端可按字段名定位。
     */
    record FieldErrorDetail(String field, String message, Object rejectedValue) {
    }
}
