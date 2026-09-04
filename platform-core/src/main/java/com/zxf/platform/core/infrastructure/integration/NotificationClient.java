package com.zxf.platform.core.infrastructure.integration;

import com.zxf.platform.core.domain.model.NotificationFailedException;
import com.zxf.platform.core.domain.port.NotificationPort;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

/**
 * 通知端口实现（文档 7.7.2 组件 11）：RestClient + Resilience4j（CircuitBreaker + Retry）包装下游 HTTP 调用。
 *
 * <p><b>装饰顺序</b>：Retry 内、CircuitBreaker 外（每次重试都经 CB 计数）。
 * 即 {@code CircuitBreaker.decorateRunnable(cb, Retry.decorateRunnable(retry, task))}——
 * 等价于 {@code Decorators.ofRunnable(task).withRetry(retry).withCircuitBreaker(cb).decorate()}，
 * 但无需引入 {@code resilience4j-all} 仅为一个 helper 类（java-coding-standard §3 按需引入）。
 *
 * <p><b>异常翻译</b>：下游任何异常（连接失败 / 4xx / 5xx）统一包装为
 * {@link NotificationFailedException}，交由 Flowable Job 重试→死信机制处理
 * （文档 7.7.1 组件 4 技术异常路径示范）。cause 保留以满足异常链规范（java-coding-standard §6.1）。
 *
 * <p>同步调用：暂不需要 {@code ContextPropagator}——当前由 Flowable Job 线程
 * 直调，{@code CorrelationIdInterceptor} 在同线程读取 MDC / {@code EntityContext} 即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationClient implements NotificationPort {

    private final RestClient notificationRestClient;
    private final CircuitBreaker notificationCircuitBreaker;
    private final Retry notificationRetry;

    @Override
    public void send(String orderId, String processInstanceId) {
        Assert.hasText(orderId, "orderId 不能为空");
        Assert.hasText(processInstanceId, "processInstanceId 不能为空");
        // 装饰顺序：Retry 包裹实际调用，CircuitBreaker 包裹 Retry（每次重试计入 CB 统计）
        var retryDecorated = Retry.decorateRunnable(notificationRetry,
                () -> doCall(orderId, processInstanceId));
        var fullyDecorated = CircuitBreaker.decorateRunnable(notificationCircuitBreaker, retryDecorated);
        try {
            fullyDecorated.run();
        } catch (CallNotPermittedException e) {
            // 熔断 OPEN 期拒绝（未真正触达下游）：WARN 明示 + 消息前缀 CIRCUIT_OPEN——
            // 死信的 exceptionMessage 据此区分"熔断期空耗"与"真实下游故障"（评审建议：
            // 熔断窗口内 Job 以 PT5S 节奏瞬时失败直至死信，告警需分流两类根因）
            log.warn("通知熔断器 OPEN，调用被拒绝（未触达下游）orderId={}", orderId);
            throw new NotificationFailedException(
                    "CIRCUIT_OPEN：通知熔断器打开，未调用下游 orderId=" + orderId, e);
        } catch (Exception e) {
            throw new NotificationFailedException(
                    "通知下游失败 orderId=" + orderId + ": " + e.getMessage(), e);
        }
    }

    private void doCall(String orderId, String processInstanceId) {
        // 下游调用日志（downstream-conventions §8）：DEBUG 级记录 URL + 方法，不记请求体（可能含敏感数据）
        log.debug("Calling notification downstream, method: POST, uri: /api/v1/notifications, orderId: {}",
                orderId);
        notificationRestClient.post()
                .uri("/api/v1/notifications")
                .body(Map.of("orderId", orderId, "processInstanceId", processInstanceId))
                .retrieve()
                .toBodilessEntity();
    }
}
