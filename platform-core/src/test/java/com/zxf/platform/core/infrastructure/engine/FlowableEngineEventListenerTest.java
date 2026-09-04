package com.zxf.platform.core.infrastructure.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link FlowableEngineEventListener} 的 entity 维度解析契约（评审修复 M4）：
 * async Job 事件在 delegate 重建窗口外触发、线程上下文恒空——指标此前永久打
 * {@code entity=none}；修复后经 {@code RuntimeService} 从 {@code entity} 流程变量兜底。
 */
class FlowableEngineEventListenerTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final RuntimeService runtimeService = mock(RuntimeService.class);

    @AfterEach
    void cleanUp() {
        EntityContext.clear();
    }

    @Test
    void 同步事件从线程上下文取标且不查引擎() {
        EntityContext.set(EntityType.ALPHA);
        var listener = listenerWith(runtimeService);

        listener.onEvent(engineEvent(FlowableEngineEventType.PROCESS_STARTED, "exec-1"));

        assertThat(entityTag()).isEqualTo("ALPHA");
        org.mockito.Mockito.verifyNoInteractions(runtimeService);
    }

    @Test
    void 线程无上下文时从流程变量兜底取标() {
        // 模拟 Job 线程：acquisition 提交、delegate 重建窗口外，ThreadLocal 为空——
        // 兜底经 RuntimeService 读 executionId 作用域上的 entity 变量
        when(runtimeService.getVariable("exec-42", EntityContextAwareDelegate.ENTITY_VARIABLE)).thenReturn("BETA");
        var listener = listenerWith(runtimeService);

        listener.onEvent(engineEvent(FlowableEngineEventType.JOB_EXECUTION_FAILURE, "exec-42"));

        assertThat(entityTag()).isEqualTo("BETA");
        // 桥接事件同样携带兜底标（业务监听方消费同一事实）
        var bridged = ArgumentCaptor.forClass(FlowableProcessEvent.class);
        org.mockito.Mockito.verify(eventPublisher).publishEvent(bridged.capture());
        assertThat(bridged.getValue().entity()).isEqualTo(EntityType.BETA);
    }

    @Test
    void 兜底查询失败时静默降级none() {
        // 观测兜底不得影响业务（isFailOnException=false 纪律）：如 SUCCESS 后 execution 已推进/删除
        when(runtimeService.getVariable(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("execution 不存在"));
        var listener = listenerWith(runtimeService);

        listener.onEvent(engineEvent(FlowableEngineEventType.JOB_EXECUTION_SUCCESS, "exec-gone"));

        assertThat(entityTag()).isEqualTo("none");
    }

    @Test
    void 无executionId的引擎级事件保持none且零查询() {
        var listener = listenerWith(runtimeService);

        listener.onEvent(engineEvent(FlowableEngineEventType.JOB_CANCELED, null));

        assertThat(entityTag()).isEqualTo("none");
        org.mockito.Mockito.verifyNoInteractions(runtimeService);
    }

    private String entityTag() {
        var counter = meterRegistry.find("flowable.engine.events").counter();
        assertThat(counter).isNotNull();
        return counter.getId().getTag("entity");
    }

    @SuppressWarnings("unchecked")
    private FlowableEngineEventListener listenerWith(RuntimeService runtimeService) {
        ObjectProvider<RuntimeService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(runtimeService);
        return new FlowableEngineEventListener(meterRegistry, eventPublisher, provider);
    }

    /** 构造携带（可选）executionId 的引擎事件 mock。 */
    private static FlowableEngineEvent engineEvent(FlowableEngineEventType type, String executionId) {
        var event = mock(FlowableEngineEvent.class);
        when(event.getType()).thenReturn(type);
        when(event.getExecutionId()).thenReturn(executionId);
        return event;
    }
}
