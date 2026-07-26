package com.zxf.platform.core.application.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zxf.platform.core.domain.model.Order;
import com.zxf.platform.core.domain.model.OrderContext;
import com.zxf.platform.core.domain.port.OrderStep;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** 文档 5.8.1：管道按装配顺序执行；零步骤装配启动期 fail-fast（{@code @PostConstruct} 校验）。 */
class OrderPipelineTest {

    @Test
    void 零步骤装配启动失败() {
        // 校验在 @PostConstruct（OrderPipeline.validateAssembly），纯 new 不触发——
        // 用 ApplicationContext 启动让 Spring 走完整 bean 生命周期（含 @PostConstruct），
        // 断言"初始化失败"（启动期 fail-fast，时机等价于原构造期校验）
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(OrderPipeline.class);
            // 容器中没有任何 OrderStep bean → Spring 注入空 List → @PostConstruct 抛 IllegalArgumentException
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OrderStep");
        }
    }

    @Test
    void 按装配顺序执行全部步骤() {
        var calls = new ArrayList<String>();
        var pipeline = new OrderPipeline(List.of(step("first", calls), step("second", calls)));

        pipeline.run(new OrderContext(Order.from("widget", 1)));

        assertThat(calls).containsExactly("first", "second");
    }

    @Test
    void stepNames按装配顺序返回() {
        var pipeline = new OrderPipeline(
                List.of(step("schema-validation", new ArrayList<>()), step("risk-check", new ArrayList<>())));

        assertThat(pipeline.stepNames()).containsExactly("schema-validation", "risk-check");
    }

    private static OrderStep step(String name, List<String> calls) {
        return new OrderStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void execute(OrderContext ctx) {
                calls.add(name);
            }
        };
    }
}
