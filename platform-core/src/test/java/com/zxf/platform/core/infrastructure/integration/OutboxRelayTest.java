package com.zxf.platform.core.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.PlatformProperties;
import com.zxf.platform.core.domain.model.OutboxDeliveryStatus;
import com.zxf.platform.core.domain.model.OutboxEvent;
import com.zxf.platform.core.domain.port.OutboxRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link OutboxRelay} 投递治理契约（评审修复 P3：重投上限与死信出口）：
 * 持续失败的事件计数达 {@code MAX_ATTEMPTS} 转 DEAD（不再被扫描），单条失败不阻塞同轮
 * 其余事件。直调 relay()（无 Spring 上下文，@Transactional 不生效）——断言领域对象
 * 内存态，落库路径由 e2e 覆盖。
 */
class OutboxRelayTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);

    @Test
    void 投递成功标记已发布() {
        var event = new OutboxEvent("ORDER", "1", "ORDER_CREATED", null);
        when(repository.findUnpublished(10)).thenReturn(List.of(event));

        relay().relay();

        assertThat(event.publishedAt()).isNotNull();
        assertThat(event.status()).isEqualTo(OutboxDeliveryStatus.PENDING);
    }

    @Test
    void 持续失败达上限转死信() {
        var event = new OutboxEvent("ORDER", "1", "ORDER_CREATED", null);
        when(repository.findUnpublished(10)).thenAnswer(invocation -> List.of(event));
        var relay = failingRelay();

        for (int round = 0; round < OutboxEvent.MAX_ATTEMPTS; round++) {
            relay.relay();
        }

        assertThat(event.attempts()).isEqualTo(OutboxEvent.MAX_ATTEMPTS);
        assertThat(event.status()).isEqualTo(OutboxDeliveryStatus.DEAD);
        assertThat(event.publishedAt()).isNull();
    }

    @Test
    void 死信未达上限前保持待投递() {
        var event = new OutboxEvent("ORDER", "1", "ORDER_CREATED", null);
        when(repository.findUnpublished(10)).thenAnswer(invocation -> List.of(event));
        var relay = failingRelay();

        relay.relay();
        relay.relay();

        assertThat(event.attempts()).isEqualTo(2);
        assertThat(event.status()).isEqualTo(OutboxDeliveryStatus.PENDING);
    }

    @Test
    void 单条失败不阻塞同轮其余事件() {
        // 一条毒消息不应阻塞整个 outbox：失败事件计数，成功事件照常标记发布
        var poisoned = new OutboxEvent("ORDER", "1", "ORDER_CREATED", null);
        var healthy = new OutboxEvent("ORDER", "2", "ORDER_CREATED", null);
        when(repository.findUnpublished(10)).thenReturn(List.of(poisoned, healthy));
        var relay = new OutboxRelay(repository, properties()) {
            @Override
            protected void deliver(OutboxEvent event) {
                if ("1".equals(event.aggregateId())) {
                    throw new IllegalStateException("模拟 MQ 投递失败");
                }
            }
        };

        relay.relay();

        assertThat(poisoned.attempts()).isEqualTo(1);
        assertThat(poisoned.publishedAt()).isNull();
        assertThat(healthy.publishedAt()).isNotNull();
    }

    /** deliver 恒失败（测试 seam：覆写注入失败，模拟真实 MQ 持续不可用）。 */
    private OutboxRelay failingRelay() {
        return new OutboxRelay(repository, properties()) {
            @Override
            protected void deliver(OutboxEvent event) {
                throw new IllegalStateException("模拟 MQ 投递失败");
            }
        };
    }

    private OutboxRelay relay() {
        return new OutboxRelay(repository, properties());
    }

    private PlatformProperties properties() {
        return new PlatformProperties(EntityType.ALPHA);
    }
}
