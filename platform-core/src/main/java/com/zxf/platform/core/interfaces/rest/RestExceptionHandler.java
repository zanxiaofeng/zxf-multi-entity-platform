package com.zxf.platform.core.interfaces.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 异常映射（文档 5.1.1 interfaces.rest）：应用层抛出的参数违例 → 400。
 *
 * <p>Schema 校验失败（{@link IllegalArgumentException}）属客户端可修正的输入问题，
 * 返回 RFC 9457 ProblemDetail。骨架未引入 {@code BusinessException} 体系
 * （面向后续业务模块，见 CLAUDE.md 适用范围），本 advice 只覆盖 demo 需要的映射。
 */
@Slf4j
@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("请求被拒绝: {}", ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("请求参数不合法");
        return problem;
    }
}
