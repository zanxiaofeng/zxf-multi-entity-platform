package com.zxf.platform.core.context;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 实体上下文（ThreadLocal 持有）。
 *
 * <p><b>适用边界：仅限同步 Servlet 栈</b>（Tomcat/Jetty + Spring MVC）。WebFlux 下
 * {@code OncePerRequestFilter} 不生效，需改用 {@code WebFilter} + Reactor Context（文档 5.2.2）。
 *
 * <p>ThreadLocal 不跨线程传播：{@code @Async} / 消息消费等场景必须显式传播，
 * 见 {@code AsyncConfig.entityContextPropagator}（文档 5.2.3）。
 */
public final class EntityContext {

    /**
     * MDC key：日志实体维度的唯一事实来源，Filter / TaskDecorator / delegate 基类统一引用。
     * 与 {@code application.yaml} 日志 pattern 的 {@code %X{entity:-none}} 对应，改动需同步。
     */
    public static final String MDC_KEY = "entity";

    private static final ThreadLocal<EntityType> HOLDER = new ThreadLocal<>();

    private EntityContext() {
    }

    public static void set(EntityType type) {
        HOLDER.set(type);
    }

    /**
     * 当前实体；未初始化时抛异常——业务路径不允许静默缺失实体
     * （定价类逻辑在空上下文中取策略是资损级风险）。
     */
    public static EntityType current() {
        return Optional.ofNullable(HOLDER.get())
                .orElseThrow(() -> new IllegalStateException("EntityContext 未初始化"));
    }

    /**
     * 当前实体或 {@code null}：仅用于上下文传播等允许缺失的基础设施场景。
     * JSpecify 标注让 IDE 与静态检查在编译期拦截 NPE（文档 5.0）。
     */
    public static @Nullable EntityType currentOrNull() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
