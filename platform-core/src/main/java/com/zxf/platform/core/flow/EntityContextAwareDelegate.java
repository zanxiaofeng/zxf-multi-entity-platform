package com.zxf.platform.core.flow;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.MDC;

/**
 * delegate 基类：双保险闭环（文档 7.3③）。所有 delegate 必须继承本类
 * （实体模块 ArchUnit 守护），禁止直接 {@code implements JavaDelegate}。
 *
 * <p>两条执行路径：
 * <ul>
 *   <li><b>同步 delegate</b>：运行在发起方请求线程，{@code EntityContext} / MDC 本就在，
 *       直接执行、不动上下文；</li>
 *   <li><b>async 节点</b>：由引擎 Job 执行器线程运行。Job 由引擎 acquisition 线程从
 *       {@code ACT_RU_JOB} 拉取后提交，{@link EntityContextPropagatingTaskExecutor} 在提交时
 *       捕获不到请求上下文（装饰空转）——此处从流程变量 {@value #ENTITY_VARIABLE}
 *       （启动实例时由 {@code OrderApprovalService} 写入）重建上下文与 MDC，
 *       保证 Job 线程的日志与策略解析同样落在正确实体上，执行后彻底清理。</li>
 * </ul>
 *
 * <p>变量缺失时不臆造上下文（保持缺失语义，{@code EntityContext.current()} 会在业务路径
 * 上 fail-loud）；变量值非法时 {@code valueOf} 直接抛错——流程数据被污染应当显式失败。
 */
public abstract class EntityContextAwareDelegate implements JavaDelegate {

    /** 流程变量 key：启动实例时写入的实体标识（发起方与 delegate 之间的契约）。 */
    public static final String ENTITY_VARIABLE = "entity";

    @Override
    public final void execute(DelegateExecution execution) {
        if (EntityContext.currentOrNull() != null) {
            doExecute(execution);
            return;
        }
        if (!(execution.getVariable(ENTITY_VARIABLE) instanceof String entityName)) {
            doExecute(execution);
            return;
        }
        EntityContext.set(EntityType.valueOf(entityName));
        MDC.put(EntityContext.MDC_KEY, entityName);
        try {
            doExecute(execution);
        } finally {
            MDC.remove(EntityContext.MDC_KEY);
            EntityContext.clear();
        }
    }

    /** 真正的任务逻辑（单例无状态：禁止在字段中保存执行态，文档 8.1 规则 10）。 */
    protected abstract void doExecute(DelegateExecution execution);
}
