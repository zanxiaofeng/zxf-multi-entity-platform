package com.zxf.platform.core.infrastructure.integration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j 手动配置（文档 7.7.2 组件 11）：核心库程序式配置，不依赖 spring-boot autoconfigure。
 *
 * <p>选型理由：SB4 自动配置兼容性未验证（resilience4j-spring-boot4 模块尚新），程序式
 * Bean 声明更可控、依赖面最小（仅 circuitbreaker + retry 两个核心 jar）。
 *
 * <h3>两层重试的总账</h3>
 * <ul>
 *   <li><b>HTTP 层（本配置）</b>：Retry maxAttempts=3 + 间隔 500ms，
 *       配合 CircuitBreaker（失败率 50% 触发熔断，open 持续 30s）；</li>
 *   <li><b>流程层（BPMN failedJobRetryTimeCycle R3/PT5S）</b>：技术异常路径
 *       Resilience4j 重试耗尽后抛 {@code NotificationFailedException}，
 *       Flowable Job 按节点 {@code timeCycle} 再重试 3 次。</li>
 * </ul>
 * 总账 {@code 3 × 3 = 9} 次尝试上限——既给下游足够恢复窗口，
 * 又避免无限重试放大故障（文档 7.7.1 组件 4 重试纪律）。
 */
@Configuration
public class ResilienceConfig {

    /**
     * 通知服务熔断器：10 次调用窗口内失败率 ≥50% 即 open，30s 后进入 half-open 探活。
     */
    @Bean
    public CircuitBreaker notificationCircuitBreaker() {
        var config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .build();
        return CircuitBreakerRegistry.of(config).circuitBreaker("notification");
    }

    /**
     * 通知服务重试器：3 次尝试（首调 + 2 重试），间隔 500ms。
     */
    @Bean
    public Retry notificationRetry() {
        var config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .build();
        return RetryRegistry.of(config).retry("notification");
    }
}
