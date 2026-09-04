package com.zxf.platform.core.infrastructure.engine;

import com.zxf.platform.core.context.EntityContext;
import java.lang.reflect.Method;
// Flowable 8 包路径：从 engine.impl.el 移到 common.engine.api.delegate
import org.flowable.common.engine.api.delegate.FlowableFunctionDelegate;
import org.springframework.stereotype.Component;

/**
 * 自定义 EL 函数示范（文档 7.7.1 组件 8）：流程表达式里写
 * {@code ${bpm:currentEntity()}} 替代冗长的上下文获取逻辑。
 *
 * <p>Flowable 8 经 {@code setCustomFlowableFunctionDelegates(...)} 注册——
 * 注册后 BPMN 表达式可引用 {@code ${bpm:currentEntity()}}，返回当前实体名
 * （{@code ALPHA} / {@code BETA}），无上下文时返回 {@code "none"}。
 *
 * <p><b>async 边界（评审修复 P3：明示陷阱）</b>：本函数读 ThreadLocal——表达式在引擎
 * 命令线程上求值，async 网关/任务的条件表达式不经 delegate 重建窗口（
 * {@code EntityContextAwareDelegate} 只覆盖 delegate 执行段），acquisition 提交的
 * Job 内求值恒得 {@code "none"}。同步节点（请求线程）与 delegate 内的表达式可正常取值；
 * 需要跨 async 判实体的分支应改读流程变量 {@code ${entity}}。
 *
 * <p>JSON 流程变量（组件 8 另一半）：Flowable 8 默认已内置 {@code JsonType}
 * （基于 Jackson {@code JsonNode}，支持 trackObjects 变更追踪）——无需显式注册，
 * 引擎启动即可用。文档纪律"领域值对象不直接进流程变量"仍有效（当前工程把
 * {@code orderId} 作为 String 透传是正确做法）。
 */
@Component
public class CurrentEntityElFunction implements FlowableFunctionDelegate {

    /** EL 目标方法引用：类加载时解析一次并缓存，避免每次表达式求值都走反射查找。 */
    private static final Method CURRENT_ENTITY_METHOD = resolveMethod();

    private static Method resolveMethod() {
        try {
            return CurrentEntityElFunction.class.getMethod("currentEntity");
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("currentEntity 静态方法不存在", ex);
        }
    }

    @Override
    public String prefix() {
        return "bpm";
    }

    @Override
    public String localName() {
        return "currentEntity";
    }

    @Override
    public Method functionMethod() {
        return CURRENT_ENTITY_METHOD;
    }

    /** EL 表达式实际调用的方法：返回当前实体名（无上下文时 "none"）。 */
    public static String currentEntity() {
        var entity = EntityContext.currentOrNull();
        return entity != null ? entity.name() : "none";
    }
}
