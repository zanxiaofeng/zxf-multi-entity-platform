package com.zxf.platform.core.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.zxf.platform.core.domain.model.NotificationFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/**
 * {@link NotificationClient} 单元测试（文档 7.7.2 组件 11）。
 *
 * <p>用 {@link MockRestServiceServer}（spring-test 内置，无需 WireMock）模拟下游：
 * 校验「Retry 在 CircuitBreaker 内」的装饰行为——成功路径只调 1 次、5xx 路径重试 3 次后抛
 * {@link NotificationFailedException}、4xx 路径不重试（downstream-conventions §4 错误分类）。
 *
 * <p>Resilience4j 配置与生产同源（{@link ResilienceConfig}），避免测试态参数与生产漂移。
 */
class NotificationClientTest {

    @Test
    void 下游成功时正常调用且只发一次请求() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.anything())
                .andRespond(MockRestResponseCreators.withSuccess());

        newClient(builder.build()).send("order-1", "pi-1");

        server.verify();
    }

    @Test
    void 下游持续5xx时重试三次后抛NotificationFailedException() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        // Retry maxAttempts=3 → 共 3 次请求；manyTimes 容忍 CB 内部探测调用（不额外加码）
        server.expect(ExpectedCount.manyTimes(), MockRestRequestMatchers.anything())
                .andRespond(MockRestResponseCreators.withServerError());

        assertThatThrownBy(() -> newClient(builder.build()).send("order-1", "pi-1"))
                .isInstanceOf(NotificationFailedException.class)
                .hasMessageContaining("order-1")
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void 下游4xx属客户端错误不重试() {
        // downstream-conventions §4：4xx 是客户端错误（参数/认证问题），重试无意义——
        // Retry 收窄 retryExceptions 后首次失败即抛，只发 1 次请求
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), MockRestRequestMatchers.anything())
                .andRespond(MockRestResponseCreators.withBadRequest());

        assertThatThrownBy(() -> newClient(builder.build()).send("order-1", "pi-1"))
                .isInstanceOf(NotificationFailedException.class);

        server.verify();
    }

    @Test
    void 异常包装保留原始cause供排查() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.manyTimes(), MockRestRequestMatchers.anything())
                .andRespond(MockRestResponseCreators.withServerError());

        // 异常链规范（java-coding-standard §6.1）：包装异常必须保留 cause
        Throwable thrown = catchThrowable(() -> newClient(builder.build()).send("order-1", "pi-1"));

        assertThat(thrown).isInstanceOf(NotificationFailedException.class);
        assertThat(thrown.getCause()).isNotNull();
    }

    @Test
    void 熔断打开期拒绝调用且异常可区分() {
        // 评审修复 P3：CB OPEN 期 CallNotPermittedException 单独分流——消息前缀 CIRCUIT_OPEN，
        // 死信告警据此区分"熔断期空耗"（未触达下游，恢复窗口后复活即可）与"真实下游故障"
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        // 第一次 send：Retry 3 次全部 5xx（单调用窗口熔断配置下一次失败即 open）
        server.expect(ExpectedCount.times(3), MockRestRequestMatchers.anything())
                .andRespond(MockRestResponseCreators.withServerError());
        var circuitBreaker = io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.of(
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .minimumNumberOfCalls(1)
                        .slidingWindowSize(1)
                        .waitDurationInOpenState(java.time.Duration.ofSeconds(30))
                        .build()).circuitBreaker("notification-cb-open");
        var client = new NotificationClient(builder.build(), circuitBreaker,
                new ResilienceConfig().notificationRetry());

        catchThrowable(() -> client.send("order-1", "pi-1")); // 3 次失败，窗口内失败率 100% → open

        Throwable second = catchThrowable(() -> client.send("order-1", "pi-1"));

        assertThat(second).isInstanceOf(NotificationFailedException.class)
                .hasMessageContaining("CIRCUIT_OPEN");
        assertThat(second.getCause())
                .isInstanceOf(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);
        // OPEN 期未触达下游：请求总数停留在第一次的 3 次（第二次 0 次）
        server.verify();
    }

    /** 与生产配置同源（{@link ResilienceConfig}）：CB / Retry 参数改动后测试自动跟随。 */
    private NotificationClient newClient(RestClient restClient) {
        var resilience = new ResilienceConfig();
        return new NotificationClient(restClient,
                resilience.notificationCircuitBreaker(), resilience.notificationRetry());
    }
}
