package com.zxf.platform.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 文档 5.2.3：ThreadLocal 不跨线程传播，@Async 路径必须经 TaskDecorator 传播——
 * 否则 PolicyRegistry 在错误/空上下文中取策略，对定价类逻辑是资损级风险。
 */
class EntityContextPropagatorTest {

    private final AsyncConfig config = new AsyncConfig();

    @AfterEach
    void tearDown() {
        MDC.clear();
        EntityContext.clear();
    }

    @Test
    void 装饰后的任务在另一线程继承实体上下文() throws Exception {
        EntityContext.set(EntityType.ALPHA);
        MDC.put("entity", "ALPHA");

        var seenContext = new AtomicReference<EntityType>();
        var seenMdc = new AtomicReference<String>();
        var leftover = new AtomicReference<EntityType>();

        Runnable decorated = config.entityContextPropagator().decorate(() -> {
            seenContext.set(EntityContext.current());
            seenMdc.set(MDC.get("entity"));
        });

        var thread = new Thread(() -> {
            decorated.run();
            leftover.set(EntityContext.currentOrNull()); // 执行后该线程不得残留
        });
        thread.start();
        thread.join();

        assertThat(seenContext.get()).isEqualTo(EntityType.ALPHA);
        assertThat(seenMdc.get()).isEqualTo("ALPHA");
        assertThat(leftover.get()).isNull();
    }

    @Test
    void 全量MDC快照随车传播含traceId() throws Exception {
        // logging.md traceId 全链路：请求线程提交的 @Async 任务日志应与请求同 trace
        EntityContext.set(EntityType.ALPHA);
        MDC.put("entity", "ALPHA");
        MDC.put("traceId", "trace-abc-123");

        var seenTrace = new AtomicReference<String>();
        var leftoverTrace = new AtomicReference<String>();

        Runnable decorated = config.entityContextPropagator().decorate(() ->
                seenTrace.set(MDC.get("traceId")));

        var thread = new Thread(() -> {
            decorated.run();
            leftoverTrace.set(MDC.get("traceId")); // 执行后该线程不得残留
        });
        thread.start();
        thread.join();

        assertThat(seenTrace.get()).isEqualTo("trace-abc-123");
        assertThat(leftoverTrace.get()).isNull();
    }

    @Test
    void 无上下文时装饰不炸且原样执行() {
        var ran = new AtomicReference<Boolean>(false);
        config.entityContextPropagator().decorate(() -> ran.set(true)).run();
        assertThat(ran.get()).isTrue();
    }
}
