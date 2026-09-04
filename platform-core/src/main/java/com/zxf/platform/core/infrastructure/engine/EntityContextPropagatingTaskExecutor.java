package com.zxf.platform.core.infrastructure.engine;

import com.zxf.platform.core.context.ContextSnapshot;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;

/**
 * 上下文传播执行器（文档 7.3③）：提交任务时复制 EntityContext + 全量 MDC 快照
 * （entity / traceId 随车，与 {@code AsyncConfig} 装饰器同一语义）。
 *
 * <p>Flowable 的 async 节点与 Job 执行器（AsyncExecutor）运行在<b>引擎自有线程池</b>，
 * 5.2.3 的 {@code TaskDecorator} 不适用（那是 Spring {@code @Async} 的扩展点）——
 * 因此在引擎任务入口显式包装传播。实现 Spring 的 {@link AsyncTaskExecutor}，
 * 经 Flowable 官方适配器 {@code SpringAsyncTaskExecutor} 接入引擎。
 *
 * <p>边界：acquisition 线程从 {@code ACT_RU_JOB} 拉取的历史 Job，提交时捕获不到请求
 * 上下文（装饰空转）——entity 由 delegate 基类从流程变量重建；traceId 不重建（保持
 * {@code none}，那是追踪系统的 span 语义，不作流程变量传播）。
 */
@RequiredArgsConstructor
public class EntityContextPropagatingTaskExecutor implements AsyncTaskExecutor {

    private final AsyncTaskExecutor delegate;
    private final TaskDecorator decorator;

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
        // TaskDecorator 只覆盖 Runnable：Callable 走 ContextSnapshot 同款快照装饰
        return delegate.submit(ContextSnapshot.capture().wrap(task));
    }

    @Override
    public CompletableFuture<Void> submitCompletable(Runnable task) {
        return delegate.submitCompletable(decorator.decorate(task));
    }

    @Override
    public <T> CompletableFuture<T> submitCompletable(Callable<T> task) {
        return delegate.submitCompletable(ContextSnapshot.capture().wrap(task));
    }
}
