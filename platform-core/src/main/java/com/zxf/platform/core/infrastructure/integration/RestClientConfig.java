package com.zxf.platform.core.infrastructure.integration;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient 装配（文档 7.7.2 组件 11）：为 {@link NotificationClient} 提供下游 HTTP 客户端。
 *
 * <p>命名 Bean（{@code notificationRestClient}）：未来多个下游服务各自有专用 RestClient 时，
 * 按服务名隔离 baseUrl 与拦截器配置——避免共用一个 RestClient 在多 baseUrl 间相互覆盖。
 *
 * <p>{@link CorrelationIdInterceptor} 在此处注入：所有经由该 RestClient 发出的请求
 * 自动携带 traceId + entity 头。
 *
 * <p><b>超时</b>：connect=3s、read=5s（{@code downstream-conventions.md §3}/{@code tech-stack.md}）。
 * 缺失超时会让挂起的下游服务无限期阻塞 Flowable Job 线程——Retry/CircuitBreaker 只处理异常，
 * 对 TCP 挂起无能为力，必须由底层 {@link SimpleClientHttpRequestFactory} 兜底。
 */
@Configuration
public class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 通知服务专用 RestClient。
     *
     * @param baseUrl 下游通知服务基础地址，由 {@code platform.notification.base-url} 配置
     */
    @Bean
    public RestClient notificationRestClient(
            @Value("${platform.notification.base-url}") String baseUrl) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(new CorrelationIdInterceptor())
                .requestFactory(requestFactory)
                .build();
    }
}
