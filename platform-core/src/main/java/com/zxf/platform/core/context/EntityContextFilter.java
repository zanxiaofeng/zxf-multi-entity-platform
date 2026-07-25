package com.zxf.platform.core.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 上下文解析：边缘识别，一次路由（文档 5.2.2）。
 *
 * <p>关键点：上下文设置与 MDC 打标放在<b>同一个 Filter 的同一 try/finally 生命周期内</b>，
 * 彻底消除两个 Filter 执行顺序不确定导致的 NPE / 日志串实体问题
 * （Tomcat 线程池复用下，漏清 MDC 会让 A 实体的请求日志带上 B 的标）。
 *
 * <p>部署级实体（来自配置）优先；多实体混部时可改为从 Header/Token 解析。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EntityContextFilter extends OncePerRequestFilter {

    private final PlatformProperties properties;

    public EntityContextFilter(PlatformProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        EntityContext.set(properties.entity());
        // 日志带实体维度：Splunk 等监控体系按 entity 分别告警（文档 2.5）
        MDC.put(EntityContext.MDC_KEY, properties.entity().name());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(EntityContext.MDC_KEY);
            EntityContext.clear();
        }
    }
}
