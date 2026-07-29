package com.zxf.platform.core.infrastructure.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * RestClient 装配（文档 7.7.2 组件 11）：为 {@link NotificationClient} 提供下游 HTTP 客户端。
 *
 * <p>命名 Bean（{@code notificationRestClient}）：未来多个下游服务各自有专用 RestClient 时，
 * 按服务名隔离 baseUrl 与拦截器配置——避免共用一个 RestClient 在多 baseUrl 间相互覆盖。
 *
 * <p>{@link CorrelationIdInterceptor} 在此处注入：所有经由该 RestClient 发出的请求
 * 自动携带 traceId + entity 头。
 */
@Configuration
public class RestClientConfig {

    /**
     * 通知服务专用 RestClient。
     *
     * @param baseUrl 下游通知服务基础地址，由 {@code platform.notification.base-url} 配置
     */
    @Bean
    public RestClient notificationRestClient(
            @Value("${platform.notification.base-url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(new CorrelationIdInterceptor())
                .build();
    }
}
