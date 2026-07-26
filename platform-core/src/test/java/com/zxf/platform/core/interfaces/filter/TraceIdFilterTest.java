package com.zxf.platform.core.interfaces.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * logging.md traceId 全链路：链条内 MDC 可见、响应头回传、结束后彻底清理；
 * 上游 traceId 必须过白名单，注入载荷（CRLF 等）一律丢弃重新生成。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void 请求内traceId可见且响应头回传并清理() throws Exception {
        var seenMdc = new AtomicReference<String>();
        var response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (req, res) ->
                seenMdc.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(seenMdc.get()).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(seenMdc.get());
        // finally 已清理：线程复用后不得残留上一个请求的 traceId
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 合法上游traceId被透传() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER, "upstream-trace-001");
        var response = new MockHttpServletResponse();
        var seenMdc = new AtomicReference<String>();

        filter.doFilter(request, response, (req, res) ->
                seenMdc.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(seenMdc.get()).isEqualTo("upstream-trace-001");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("upstream-trace-001");
    }

    @Test
    void 非法上游traceId被丢弃重新生成() throws Exception {
        var request = new MockHttpServletRequest();
        // 携带 CRLF 注入载荷与过短 id：白名单拒绝，重新生成 UUID
        request.addHeader(TraceIdFilter.HEADER, "x\r\nInjected-Header: evil");
        var response = new MockHttpServletResponse();
        var seenMdc = new AtomicReference<String>();

        filter.doFilter(request, response, (req, res) ->
                seenMdc.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(seenMdc.get()).matches("[A-Za-z0-9_-]{8,128}").isNotEqualTo("x\r\nInjected-Header: evil");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(seenMdc.get());
    }

    @Test
    void 链条抛异常时仍然清理() {
        assertThatThrownBy(() ->
                        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {
                            throw new RuntimeException("boom");
                        }))
                .isInstanceOf(RuntimeException.class);

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }
}
