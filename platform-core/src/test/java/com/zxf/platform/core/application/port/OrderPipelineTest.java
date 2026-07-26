package com.zxf.platform.core.application.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zxf.platform.core.domain.model.Order;
import com.zxf.platform.core.domain.model.OrderContext;
import com.zxf.platform.core.domain.port.OrderStep;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 文档 5.8.1：管道按装配顺序执行；零步骤装配启动期 fail-fast。 */
class OrderPipelineTest {

    @Test
    void 零步骤装配启动失败() {
        assertThatThrownBy(() -> new OrderPipeline(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OrderStep");
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
