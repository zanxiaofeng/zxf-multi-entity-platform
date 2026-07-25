package com.zxf.platform.core.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.order.CreateOrderCommand;
import com.zxf.platform.core.order.Order;
import java.util.Map;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 文档 7.1：按契约 key 发起实例，businessKey 与轻量流程变量（orderId + entity 双保险）。 */
@ExtendWith(MockitoExtension.class)
class OrderApprovalServiceTest {

    @Mock
    private RuntimeService runtimeService;

    @Mock
    private ProcessInstance processInstance;

    @AfterEach
    void tearDown() {
        EntityContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 按契约key发起且只携带轻量标识变量() {
        EntityContext.set(EntityType.ALPHA);
        var order = Order.from(new CreateOrderCommand("widget", 1));
        ReflectionTestUtils.setField(order, "id", 42L); // 模拟已持久化
        when(runtimeService.startProcessInstanceByKey(eq(OrderApprovalService.ORDER_APPROVAL_KEY), eq("42"), anyMap()))
                .thenReturn(processInstance);
        when(processInstance.getProcessInstanceId()).thenReturn("pi-1");

        var service = new OrderApprovalService(runtimeService);
        assertThat(service.startApproval(order)).isEqualTo("pi-1");

        verify(runtimeService).startProcessInstanceByKey(
                eq(OrderApprovalService.ORDER_APPROVAL_KEY),
                eq("42"),
                argThat((Map<String, Object> vars) ->
                        Long.valueOf(42L).equals(vars.get("orderId")) && "ALPHA".equals(vars.get("entity"))));
    }
}
