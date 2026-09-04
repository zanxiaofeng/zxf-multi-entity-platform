package com.zxf.platform.core.context;

import java.util.Map;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * 线程上下文快照（文档 5.2.3）：提交时捕获 {@link EntityContext} + <b>全量 MDC 快照</b>
 * （entity / traceId / 未来新增 key 一次覆盖），执行时设置、finally 彻底清理。
 * 快照式传播比逐 key 复制更不易漏——新 MDC 维度（如 logging.md 的 traceId）自动随车。
 *
 * <p>消费方：{@code AsyncConfig.entityContextPropagator}（Spring {@code @Async} 的
 * TaskDecorator）与 {@code EntityContextPropagatingTaskExecutor}（Flowable Job 执行器，
 * TaskDecorator 只覆盖 Runnable，Callable 走本类 {@link #wrap(Callable)}）——此前两处
 * 手写同款装饰逻辑（约 40 行重复），改一处漏一处的漂移风险真实发生过（M8 防御性清理
 * 曾需同步改两处），收敛到本类单点维护。
 *
 * <p>空快照（entity 与 mdc 均为 null）时 {@code apply} 空操作，但 finally 清理仍然
 * 执行（M8）：池化工作线程可能残留上个任务的 MDC / 上下文——不传播任何东西，
 * 但保证本任务结束后线程干净。
 */
public record ContextSnapshot(@Nullable EntityType entity, @Nullable Map<String, String> mdc) {

    /** 在提交线程捕获当前上下文快照。 */
    public static ContextSnapshot capture() {
        return new ContextSnapshot(EntityContext.currentOrNull(), MDC.getCopyOfContextMap());
    }

    /** 包装 {@link Runnable}：执行前应用快照，执行后 finally 清理（线程池复用防串实体/串请求）。 */
    public Runnable wrap(Runnable task) {
        return () -> {
            apply();
            try {
                task.run();
            } finally {
                clear();
            }
        };
    }

    /** 包装 {@link Callable}：与 {@link #wrap(Runnable)} 同一语义（TaskDecorator 只覆盖 Runnable）。 */
    public <T> Callable<T> wrap(Callable<T> task) {
        return () -> {
            apply();
            try {
                return task.call();
            } finally {
                clear();
            }
        };
    }

    private void apply() {
        if (mdc != null) {
            MDC.setContextMap(mdc);
        }
        if (entity != null) {
            EntityContext.set(entity);
            // 编程式 set（绕过 Filter）时快照可能缺 entity key，兜底补齐
            MDC.put(EntityContext.MDC_KEY, entity.name());
        }
    }

    private static void clear() {
        MDC.clear();
        EntityContext.clear();
    }
}
