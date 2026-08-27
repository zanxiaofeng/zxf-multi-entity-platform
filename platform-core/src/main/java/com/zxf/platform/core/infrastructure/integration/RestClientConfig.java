package com.zxf.platform.core.infrastructure.integration;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
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
 *
 * <p><b>请求工厂</b>：注入 Boot 自动配置的 {@link RestClient.Builder} 并以
 * {@link ClientHttpRequestFactoryBuilder#detect()} 选型——classpath 引入 httpclient5 后
 * 自动切换其连接池（带 stale 连接校验），规避 JDK {@code HttpURLConnection} keep-alive
 * 复用被服务端关闭的空闲连接的 {@code EOF reached while reading} 问题（tech-stack.md
 * Infrastructure 节）。demo 的 restclient classpath 未含 httpclient5 时 detect 回退 JDK 栈，
 * 行为与此前一致。
 *
 * <p><b>超时</b>：connect=3s、read=5s（{@code downstream-conventions.md §3}/{@code tech-stack.md}）。
 * 缺失超时会让挂起的下游服务无限期阻塞 Flowable Job 线程——Retry/CircuitBreaker 只处理异常，
 * 对 TCP 挂起无能为力，必须由底层请求工厂兜底。
 */
@Configuration
public class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 通知服务专用 RestClient。
     *
     * @param builder Boot 自动配置的 RestClient 构建器（请求工厂按 classpath 检测选型）
     * @param baseUrl 下游通知服务基础地址，由 {@code platform.notification.base-url} 配置
     */
    @Bean
    public RestClient notificationRestClient(
            RestClient.Builder builder,
            @Value("${platform.notification.base-url}") String baseUrl) {
        var settings = HttpClientSettings.defaults()
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        return builder
                .baseUrl(baseUrl)
                .requestInterceptor(new CorrelationIdInterceptor())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
