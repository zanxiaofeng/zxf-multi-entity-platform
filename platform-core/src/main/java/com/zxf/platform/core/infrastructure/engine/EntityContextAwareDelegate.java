package com.zxf.platform.core.infrastructure.engine;

import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.MDC;

/**
 * delegate 基类：双保险闭环（文档 7.3③） + 横切观测能力（文档 7.7.1 组件 3）。
 * 所有 delegate 必须继承本类（实体模块 ArchUnit 守护），禁止直接 {@code implements JavaDelegate}。
 *
 * <p><b>横切能力（组件 3）</b>：在 {@link #doExecute(DelegateExecution)} 外围统一提供
 * <ul>
 *   <li>入口/出口执行日志（delegate 名 + orderId + processInstanceId）；</li>
 *   <li>Micrometer {@link Timer} 耗时统计（tag=delegate/entity/outcome）；</li>
 *   <li>{@link BpmnError} 记 WARN 后传播（业务错误走 BPMN 分支，不重试）；</li>
 *   <li>其它 {@link Exception} 记 ERROR 后传播（走 Job 重试→死信）。</li>
 * </ul>
 *
 * <p><b>上下文双保险</b>——两条执行路径：
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
 *
 * <p>traceId 说明：从 {@code ACT_RU_JOB} 恢复的 Job 没有请求上下文，traceId 不随流程变量
 * 重建（span 续链属追踪系统职责，非流程数据），此类 Job 线程日志 {@code trace=none} 属预期。
 *
 * <p>delegate 单例无状态（文档 8.1 规则 10）：禁止在字段中保存执行态，多 Job 线程
 * 并发调用同一 Spring 单例。{@link #meterRegistry} 为单例不可变依赖，不构成执行态。
 */
@Slf4j
public abstract class EntityContextAwareDelegate implements JavaDelegate {

    /** 流程变量 key：启动实例时写入的实体标识（发起方与 delegate 之间的契约）。 */
    public static final String ENTITY_VARIABLE = "entity";

    /** Timer 指标名：所有 delegate 执行共用，按 tag 区分 delegate/entity/outcome。 */
    public static final String TIMER_NAME = "flowable.delegate.execution";

    protected final MeterRegistry meterRegistry;

    protected EntityContextAwareDelegate(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public final void execute(DelegateExecution execution) {
        if (EntityContext.currentOrNull() != null) {
            executeWithObservation(execution);
            return;
        }
        if (!(execution.getVariable(ENTITY_VARIABLE) instanceof String entityName)) {
            executeWithObservation(execution);
            return;
        }
        EntityContext.set(EntityType.valueOf(entityName));
        MDC.put(EntityContext.MDC_KEY, entityName);
        try {
            executeWithObservation(execution);
        } finally {
            MDC.remove(EntityContext.MDC_KEY);
            EntityContext.clear();
        }
    }

    /** 真正的任务逻辑（单例无状态：禁止在字段中保存执行态，文档 8.1 规则 10）。 */
    protected abstract void doExecute(DelegateExecution execution);

    /**
     * 在 {@link #doExecute(DelegateExecution)} 外围统一施加横切观测能力（组件 3）：
     * 入口/出口日志、Timer 计时、异常分类（BpmnError→WARN / 其它→ERROR）后原样传播。
     *
     * <p>异常传播策略与 Flowable Job 重试/死信机制衔接：
     * <ul>
     *   <li>{@link BpmnError} 是业务错误，由 BPMN 边界错误事件捕获走分支，
     *       <b>不</b>触发 Job 重试（文档 7.7.1 组件 5）；</li>
     *   <li>其它 {@link Exception} 是技术异常，配合 {@code failedJobRetryTimeCycle}
     *       重试，耗尽进 {@code ACT_RU_DEADLETTER_JOB}（文档 7.7.1 组件 4）。</li>
     * </ul>
     */
    private void executeWithObservation(DelegateExecution execution) {
        var delegateName = getClass().getSimpleName();
        var orderId = execution.getVariable("orderId");
        log.info("delegate 执行开始 name={} orderId={} processInstanceId={}",
                delegateName, orderId, execution.getProcessInstanceId());
        var sample = Timer.start(meterRegistry);
        try {
            doExecute(execution);
            stopTimer(sample, delegateName, "success");
            log.info("delegate 执行完成 name={} orderId={}", delegateName, orderId);
        } catch (BpmnError e) {
            stopTimer(sample, delegateName, "bpmn-error");
            log.warn("delegate 业务错误 name={} orderId={} errorCode={}", delegateName, orderId, e.getErrorCode());
            throw e;
        } catch (Exception e) {
            stopTimer(sample, delegateName, "error");
            log.error("delegate 技术异常 name={} orderId={}", delegateName, orderId, e);
            throw e;
        }
    }

    /** 统一 Timer 注册：消除三路重复的 builder 链 + 统一 outcome tag（含 success 路径）。 */
    private void stopTimer(Timer.Sample sample, String delegateName, String outcome) {
        sample.stop(meterRegistry.timer(TIMER_NAME,
                "delegate", delegateName, "entity", entityTag(), "outcome", outcome));
    }

    private String entityTag() {
        var entity = EntityContext.currentOrNull();
        return entity != null ? entity.name() : "none";
    }
}
