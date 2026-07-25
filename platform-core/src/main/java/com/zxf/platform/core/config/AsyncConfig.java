package com.zxf.platform.core.config;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 上下文传播（异步场景，文档 5.2.3）。
 *
 * <p>ThreadLocal 不跨线程传播，{@code @Async} / 自定义线程池必须用 {@link TaskDecorator}
 * 显式传播，否则 {@code PolicyRegistry} 会在错误/空上下文中取策略——对定价类逻辑是资损级风险。
 *
 * <p>{@code CompletableFuture} 手工提交线程池同样需包装，或显式把 {@code EntityType}
 * 作为参数传入异步方法（更显式，推荐核心业务路径使用）。虚拟线程下 ThreadLocal 语义不变，
 * 本方案同样适用。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public TaskDecorator entityContextPropagator() {
        return runnable -> {
            EntityType entity = EntityContext.currentOrNull();
            return () -> {
                if (entity == null) {
                    runnable.run();
                    return;
                }
                EntityContext.set(entity);
                MDC.put("entity", entity.name());
                try {
                    runnable.run();
                } finally {
                    MDC.remove("entity");
                    EntityContext.clear();
                }
            };
        };
    }

    /**
     * 显式声明 {@code @Async} 执行器并挂上 {@link TaskDecorator}，
     * 确保传播一定生效（覆盖 Boot 默认执行器，不依赖自动配置是否收集 decorator）。
     */
    @Bean(name = "applicationTaskExecutor")
    public ThreadPoolTaskExecutor applicationTaskExecutor(TaskDecorator entityContextPropagator) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("entity-async-");
        executor.setTaskDecorator(entityContextPropagator);
        return executor;
    }
}
