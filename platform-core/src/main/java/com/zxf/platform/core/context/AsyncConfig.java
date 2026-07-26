package com.zxf.platform.core.context;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

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
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /**
     * {@code @Async} void 方法的未捕获异常没有任何调用方可接收——不配 handler 会静默丢失
     * （exception-handling §7.3 / 反模式 #11）。统一在此 ERROR 记录（MDC 已由
     * {@link #entityContextPropagator} 装饰器写好，日志带实体维度）。
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("@Async 未捕获异常 method={} params={}", method.getName(), params, ex);
    }

    /**
     * {@code @Async} 传播装饰器：提交时捕获 {@link EntityContext} + <b>全量 MDC 快照</b>
     * （entity / traceId / 未来新增 key 一次覆盖），执行时设置、finally 彻底清理。
     * 快照式传播比逐 key 复制更不易漏——新 MDC 维度（如 logging.md 的 traceId）自动随车。
     */
    @Bean
    public TaskDecorator entityContextPropagator() {
        return runnable -> {
            EntityType entity = EntityContext.currentOrNull();
            Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
            if (entity == null && mdcSnapshot == null) {
                return runnable;
            }
            return () -> {
                if (mdcSnapshot != null) {
                    MDC.setContextMap(mdcSnapshot);
                }
                if (entity != null) {
                    EntityContext.set(entity);
                    // 编程式 set（绕过 Filter）时快照可能缺 entity key，兜底补齐
                    MDC.put(EntityContext.MDC_KEY, entity.name());
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                    EntityContext.clear();
                }
            };
        };
    }

    /**
     * 显式声明 {@code @Async} 执行器：虚拟线程（每任务一线程，不池化）+ {@link TaskDecorator}。
     *
     * <p><b>必须 @Primary</b>：{@code @Async} 无 qualifier 时按类型取唯一 {@code TaskExecutor}，
     * 取不到则静默退回无装饰器的 {@code SimpleAsyncTaskExecutor}——上下文丢失且无报错。
     * 工程里还有 {@code flowableJobExecutor}（文档 7.3③）等第二个执行器 bean，靠 @Primary
     * 让按类型解析收敛到本执行器。
     *
     * <p>注：Boot 自动配置的执行器同样会应用 {@code TaskDecorator}；此处显式声明是为了把
     * 「@Primary 唯一解析」与「虚拟线程」两个语义同时钉死在代码里，不依赖自动配置的装配细节
     * （全局 {@code spring.threads.virtual.enabled=true} 对自定义执行器不生效，需自行开启）。
     */
    @Bean(name = "applicationTaskExecutor")
    @Primary
    public SimpleAsyncTaskExecutor applicationTaskExecutor(TaskDecorator entityContextPropagator) {
        var executor = new SimpleAsyncTaskExecutor("entity-async-");
        executor.setVirtualThreads(true);
        executor.setTaskDecorator(entityContextPropagator);
        return executor;
    }
}
