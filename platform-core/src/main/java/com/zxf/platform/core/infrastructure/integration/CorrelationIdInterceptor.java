package com.zxf.platform.core.infrastructure.integration;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.interfaces.filter.TraceIdFilter;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * correlationId 透传拦截器（文档 7.7.2 组件 11）：下游调用注入 traceId + entity 头。
 *
 * <p>从当前线程 MDC / {@link EntityContext} 读取（同步调用栈下由 Filter / delegate 基类写入），
 * 注入下游 HTTP 请求头，形成跨服务链路关联：
 * <ul>
 *   <li>{@code X-Trace-Id}：与 {@link TraceIdFilter} 同名头一致，沿用白名单后的值；</li>
 *   <li>{@code X-Entity}：当前实体维度，下游可据此分租户/分账。</li>
 * </ul>
 *
 * <p>缺失时不臆造——无 traceId 的线程（如未走 Filter 的调度任务）不写头，
 * 下游若依赖该头需自行兜底。
 */
public class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

    private static final String ENTITY_HEADER = "X-Entity";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        var traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null) {
            request.getHeaders().set(TraceIdFilter.HEADER, traceId);
        }
        var entity = EntityContext.currentOrNull();
        if (entity != null) {
            request.getHeaders().set(ENTITY_HEADER, entity.name());
        }
        return execution.execute(request, body);
    }
}
