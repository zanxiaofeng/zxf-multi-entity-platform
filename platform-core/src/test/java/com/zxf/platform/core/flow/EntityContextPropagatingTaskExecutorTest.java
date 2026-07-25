package com.zxf.platform.core.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.config.AsyncConfig;
import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/** 文档 7.3③：Flowable Job 执行器线程池的上下文传播（引擎自有线程，不经 Web Filter）。 */
class EntityContextPropagatingTaskExecutorTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
        EntityContext.clear();
    }

    @Test
    void 任务在另一线程继承实体上下文() throws Exception {
        // 关键点：装饰发生在提交线程（此刻捕获上下文），执行发生在工作线程
        var executor = new EntityContextPropagatingTaskExecutor(
                new SimpleAsyncTaskExecutor("flowable-job-test-"),
                new AsyncConfig().entityContextPropagator());

        EntityContext.set(EntityType.BETA);
        MDC.put("entity", "BETA");

        var seenContext = new AtomicReference<EntityType>();
        var seenMdc = new AtomicReference<String>();
        var done = new CountDownLatch(1);
        executor.execute(() -> {
            seenContext.set(EntityContext.current());
            seenMdc.set(MDC.get("entity"));
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seenContext.get()).isEqualTo(EntityType.BETA);
        assertThat(seenMdc.get()).isEqualTo("BETA");
    }

    @Test
    void 无上下文时任务原样执行() throws Exception {
        var executor = new EntityContextPropagatingTaskExecutor(
                new SimpleAsyncTaskExecutor("flowable-job-test-"),
                new AsyncConfig().entityContextPropagator());

        var ran = new CountDownLatch(1);
        executor.execute(ran::countDown);
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
