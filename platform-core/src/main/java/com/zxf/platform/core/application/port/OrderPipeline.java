package com.zxf.platform.core.application.port;

import com.zxf.platform.core.domain.model.OrderContext;
import com.zxf.platform.core.domain.port.OrderStep;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 管道编排器（文档 5.8.1）：步骤列表由装配决定，核心层零实体判断。
 *
 * <p>{@code List<OrderStep>} 由 Spring 注入当前实体装配的全部步骤，按
 * {@code @Order} 排序。与 5.2.5 同级 fail-fast：装配遗漏导致零步骤时注入空
 * List，启动即失败而非静默空转。
 *
 * <p>与 Flowable 构成"轻/重"对照：步骤增删用管道；拓扑含分支、等待、人工任务才上引擎。
 */
@Component
public class OrderPipeline {

    private final List<OrderStep> steps;

    public OrderPipeline(List<OrderStep> steps) {
        Assert.notEmpty(steps, "当前实体未装配任何 OrderStep，请检查 profile 与 platform.entity");
        this.steps = steps;
    }

    /**
     * 按 {@code @Order} 顺序执行当前实体装配的全部管道步骤。
     *
     * @param ctx 订单上下文（步骤间共享，不允许 {@code null}）
     */
    public void run(OrderContext ctx) {
        Assert.notNull(ctx, "ctx 不能为空");
        steps.forEach(step -> step.execute(ctx));
    }

    /** 装配冒烟专用（文档 5.8.1 步骤名有序比对）：包私有，仅限同包的装配测试断言。 */
    List<String> stepNames() {
        return steps.stream().map(OrderStep::name).toList();
    }
}
