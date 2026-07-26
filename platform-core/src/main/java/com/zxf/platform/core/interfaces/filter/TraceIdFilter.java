package com.zxf.platform.core.interfaces.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * traceId 全链路（logging.md）：每请求注入 MDC 并回传响应头，供跨服务日志关联。
 *
 * <p>上游 {@code X-Trace-Id} 必须白名单校验（防日志注入 / 响应头分裂），不合法则丢弃重新生成。
 *
 * <p>MDC 在 finally 清理——Tomcat 线程池复用，漏清会串请求。本 Filter 先于
 * {@link EntityContextFilter} 执行（order 差一位），两者各管各的 MDC key、各自 finally 清理，
 * 互不依赖。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC key，日志 pattern 与跨线程传播复用此常量。 */
    public static final String MDC_KEY = "traceId";

    /** 上游传入与响应回传共用的头名。 */
    public static final String HEADER = "X-Trace-Id";

    /** 白名单：只接受可打印安全字符的定长区间，拒绝 CRLF 等注入载荷。 */
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{8,128}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var traceId = Optional.ofNullable(request.getHeader(HEADER))
                .filter(id -> SAFE_TRACE_ID.matcher(id).matches())
                .orElseGet(() -> UUID.randomUUID().toString());
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
