package com.zxf.platform.core.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.zxf.platform.core.domain.model.NotificationFailedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link NotificationClient} 单元测试（文档 7.7.2 组件 11）。
 *
 * <p>用 {@link MockRestServiceServer}（spring-test 内置，无需 WireMock）模拟下游：
 * 校验「Retry 在 CircuitBreaker 内」的装饰行为——成功路径只调 1 次、失败路径重试 3 次后抛
 * {@link NotificationFailedException}。
 *
 * <p>测试态独立装配 Resilience4j（不引 spring-boot 自动配置）：
 * RetryInterval=10ms 让测试快跑；CB slidingWindow=4 让单测可触发统计。
 */
class NotificationClientTest {

    @Test
    void 下游成功时正常调用且只发一次请求() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());

        newClient(builder.build()).send("order-1", "pi-1");

        server.verify();
    }

    @Test
    void 下游持续失败时重试三次后抛NotificationFailedException() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        // Retry maxAttempts=3 → 共 3 次请求；manyTimes 容忍 CB 内部探测调用（不额外加码）
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());

        assertThatThrownBy(() -> newClient(builder.build()).send("order-1", "pi-1"))
                .isInstanceOf(NotificationFailedException.class)
                .hasMessageContaining("order-1")
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void 异常包装保留原始cause供排查() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());

        // 异常链规范（java-coding-standard §11）：包装异常必须保留 cause
        Throwable thrown = catchThrowable(() -> newClient(builder.build()).send("order-1", "pi-1"));

        assertThat(thrown).isInstanceOf(NotificationFailedException.class);
        assertThat(thrown.getCause()).isNotNull();
    }

    /** 独立装配 Resilience4j：测试态用更短的间隔，避免拉长测试时间。 */
    private NotificationClient newClient(RestClient restClient) {
        var cb = CircuitBreaker.of("notification", CircuitBreakerConfig.custom()
                .failureRateThreshold(50).slidingWindowSize(4).build());
        var retry = Retry.of("notification", RetryConfig.custom()
                .maxAttempts(3).waitDuration(Duration.ofMillis(10)).build());
        return new NotificationClient(restClient, cb, retry);
    }
}
