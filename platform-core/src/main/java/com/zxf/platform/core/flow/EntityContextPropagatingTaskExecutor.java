package com.zxf.platform.core.flow;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.slf4j.MDC;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;

/**
 * 上下文传播执行器（文档 7.3③）：提交任务时复制 EntityContext / MDC。
 *
 * <p>Flowable 的 async 节点与 Job 执行器（AsyncExecutor）运行在<b>引擎自有线程池</b>，
 * 5.2.3 的 {@code TaskDecorator} 不适用（那是 Spring {@code @Async} 的扩展点）——
 * 因此在引擎任务入口显式包装传播。实现 Spring 的 {@link AsyncTaskExecutor}，
 * 经 Flowable 官方适配器 {@code SpringAsyncTaskExecutor} 接入引擎。
 */
public class EntityContextPropagatingTaskExecutor implements AsyncTaskExecutor {

    private final AsyncTaskExecutor delegate;
    private final TaskDecorator decorator;

    public EntityContextPropagatingTaskExecutor(AsyncTaskExecutor delegate, TaskDecorator decorator) {
        this.delegate = delegate;
        this.decorator = decorator;
    }

    @Override
    public void execute(Runnable task) {
        delegate.execute(decorator.decorate(task));
    }

    /** @deprecated 接口方法已废弃，保留重写只为忠实转发 startTimeout。 */
    @Deprecated
    @Override
    public void execute(Runnable task, long startTimeout) {
        delegate.execute(decorator.decorate(task), startTimeout);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(decorator.decorate(task));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(decorate(task));
    }

    @Override
    public CompletableFuture<Void> submitCompletable(Runnable task) {
        return delegate.submitCompletable(decorator.decorate(task));
    }

    @Override
    public <T> CompletableFuture<T> submitCompletable(Callable<T> task) {
        return delegate.submitCompletable(decorate(task));
    }

    /** {@link TaskDecorator} 只覆盖 Runnable：Callable 走同款"提交时捕获、执行时设置、finally 清理"。 */
    private <T> Callable<T> decorate(Callable<T> task) {
        EntityType entity = EntityContext.currentOrNull();
        if (entity == null) {
            return task;
        }
        return () -> {
            EntityContext.set(entity);
            MDC.put(EntityContext.MDC_KEY, entity.name());
            try {
                return task.call();
            } finally {
                MDC.remove(EntityContext.MDC_KEY);
                EntityContext.clear();
            }
        };
    }
}
