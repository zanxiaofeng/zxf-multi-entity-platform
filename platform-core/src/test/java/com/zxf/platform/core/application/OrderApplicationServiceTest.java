package com.zxf.platform.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zxf.platform.core.application.command.CreateOrderCommand;
import com.zxf.platform.core.domain.event.OrderCreatedEvent;
import com.zxf.platform.core.domain.model.Money;
import com.zxf.platform.core.domain.model.Order;
import com.zxf.platform.core.domain.model.OrderStatus;
import com.zxf.platform.core.domain.port.OrderApprovalPort;
import com.zxf.platform.core.domain.port.OrderRepository;
import com.zxf.platform.core.domain.port.OutboxRepository;
import com.zxf.platform.core.application.port.OrderPipeline;
import com.zxf.platform.core.application.port.PolicyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link OrderApplicationService} 的 Outbox 事件分流契约（评审修复 M3 方案 b）：
 * 风控拒绝的订单广播 ORDER_REJECTED 而非 ORDER_CREATED——e2e 侧 relay（fixedDelay=5s）
 * 的发布时序不可控，分流逻辑在此以纯 Mockito 单测钉死（mock 出全部端口）。
 */
@ExtendWith(MockitoExtension.class)
class OrderApplicationServiceTest {

    @Mock
    private OrderRepository repository;

    @Mock
    private PolicyRegistry policies;

    @Mock
    private OrderPipeline pipeline;

    @Mock
    private OrderApprovalPort approval;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private ApplicationEventPublisher events;

    @Test
    void 正常创建广播ORDER_CREATED() {
        var service = serviceWithSavedOrder("pi-created", false);
        var order = service.create(new CreateOrderCommand("widget", 2));

        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        verify(outboxRepository).save(org.mockito.ArgumentMatchers.argThat(
                event -> "ORDER_CREATED".equals(event.eventType())));
        var eventCaptor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().status()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void 风控拒绝广播ORDER_REJECTED且事件携带终态() {
        // 模拟 Alpha 拒绝分支：同步流程在 startApproval 返回前经 riskRejectTask
        // 落账 RISK_REJECTED（与真实 BPMN 同事务同效——delegate 修改的是同一 managed 实例）
        var service = serviceWithSavedOrder("pi-rejected", true);
        var order = service.create(new CreateOrderCommand("risk-widget", 1));

        assertThat(order.status()).isEqualTo(OrderStatus.RISK_REJECTED);
        verify(outboxRepository).save(org.mockito.ArgumentMatchers.argThat(
                event -> "ORDER_REJECTED".equals(event.eventType())));
        var eventCaptor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().status()).isEqualTo(OrderStatus.RISK_REJECTED);
    }

    /** 装配被测服务：save 返回自身（反射注入 id 模拟持久化），startApproval 可选落账拒绝态。 */
    private OrderApplicationService serviceWithSavedOrder(String processInstanceId, boolean rejected) {
        when(policies.priceFor(any())).thenReturn(Money.cny("113"));
        when(repository.save(any())).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 42L);
            return order;
        });
        when(approval.startApproval(any())).thenAnswer(invocation -> {
            if (rejected) {
                Order order = invocation.getArgument(0);
                order.markRiskRejected();
            }
            return processInstanceId;
        });
        return new OrderApplicationService(repository, policies, pipeline, approval, outboxRepository, events);
    }
}
