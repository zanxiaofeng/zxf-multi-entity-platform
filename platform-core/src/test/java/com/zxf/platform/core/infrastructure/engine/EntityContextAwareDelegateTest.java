package com.zxf.platform.core.infrastructure.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 文档 7.3③ 双保险闭环：同步路径不动上下文；async Job 线程（无请求上下文）
 * 从流程变量 {@code entity} 重建并在执行后彻底清理；变量缺失不臆造。
 */
class EntityContextAwareDelegateTest {

    private final AtomicReference<EntityType> seenContext = new AtomicReference<>();
    private final AtomicReference<String> seenMdc = new AtomicReference<>();

    private final EntityContextAwareDelegate delegate = new EntityContextAwareDelegate() {
        @Override
        protected void doExecute(DelegateExecution execution) {
            seenContext.set(EntityContext.currentOrNull());
            seenMdc.set(MDC.get("entity"));
        }
    };

    @AfterEach
    void tearDown() {
        MDC.clear();
        EntityContext.clear();
    }

    @Test
    void 同步路径上下文已在则不触碰() {
        EntityContext.set(EntityType.ALPHA);
        MDC.put("entity", "ALPHA");
        var execution = executionWithEntityVariable("BETA"); // 部署级模型下不会出现，防御为主

        delegate.execute(execution);

        assertThat(seenContext.get()).isEqualTo(EntityType.ALPHA); // 以线程上下文为准，不用变量覆盖
        // 执行后现场原样保留（清理由 Filter / 装饰器负责，基类不越权）
        assertThat(EntityContext.currentOrNull()).isEqualTo(EntityType.ALPHA);
        assertThat(MDC.get("entity")).isEqualTo("ALPHA");
    }

    @Test
    void 异步Job线程从流程变量重建且执行后清理() {
        var execution = executionWithEntityVariable("BETA");

        delegate.execute(execution);

        assertThat(seenContext.get()).isEqualTo(EntityType.BETA);
        assertThat(seenMdc.get()).isEqualTo("BETA");
        // Job 线程复用后不得残留实体痕迹
        assertThat(EntityContext.currentOrNull()).isNull();
        assertThat(MDC.get("entity")).isNull();
    }

    @Test
    void 无上下文且变量缺失时原样执行不臆造() {
        var execution = mock(DelegateExecution.class);

        delegate.execute(execution);

        assertThat(seenContext.get()).isNull();
        assertThat(EntityContext.currentOrNull()).isNull();
        assertThat(MDC.get("entity")).isNull();
    }

    private static DelegateExecution executionWithEntityVariable(String entityName) {
        var execution = mock(DelegateExecution.class);
        when(execution.getVariable(EntityContextAwareDelegate.ENTITY_VARIABLE)).thenReturn(entityName);
        return execution;
    }
}
