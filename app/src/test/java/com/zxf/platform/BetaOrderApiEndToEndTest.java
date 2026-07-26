package com.zxf.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.infrastructure.observation.AuditService;
import java.time.Duration;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Beta 端到端：与 Alpha 对称，计价差异（95 折）证明请求走到了 Beta 的 SPI 实现。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("beta")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "beta")
class BetaOrderApiEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditService auditService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Test
    void 下单按Beta计价且创建后可查询() throws Exception {
        var result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"widget\",\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price.amount").value(190.00)) // 200 * 0.95，Beta 专属
                .andExpect(jsonPath("$.price.currency").value("CNY"))
                .andReturn();

        String orderId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

        String location = result.getResponse().getHeader("Location");
        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item").value("widget"))
                .andExpect(jsonPath("$.price.amount").value(190.00));

        // 流程拓扑差异外置（文档 7.2）：同 key 不同拓扑——Beta 实例停在 Beta 一级审批（无风控节点）
        var instance = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(orderId).singleResult();
        assertThat(instance).isNotNull();
        assertThat(runtimeService.getActiveActivityIds(instance.getId()))
                .containsExactly("betaApproveL1");
        assertThat(runtimeService.getVariable(instance.getId(), "entity")).isEqualTo("BETA");
        assertThat(runtimeService.getVariable(instance.getId(), "orderId")).isEqualTo(orderId);

        // 异步审计：AFTER_COMMIT 事件 + @Async 监听器，实体上下文经 TaskDecorator 传播（文档 5.2.3 / 8.1 规则 11）
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(auditService.trail()).anySatisfy(entry -> {
                    assertThat(entry.action()).isEqualTo("ORDER_CREATED");
                    assertThat(entry.entity()).isEqualTo(EntityType.BETA);
                }));
    }

    @Test
    void 审批走完后异步通知任务从流程变量重建实体上下文() throws Exception {
        var result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"gadget\",\"quantity\":1}"))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

        var instance = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(orderId).singleResult();
        assertThat(instance).isNotNull();

        // 走完五级审批 → sendNotification 是 async 节点，由引擎 Job 执行器线程运行
        Task task;
        while ((task = taskService.createTaskQuery().processInstanceId(instance.getId()).active().singleResult()) != null) {
            taskService.complete(task.getId());
        }

        // 双保险闭环（文档 7.3③）：Job 线程无请求上下文，delegate 基类从流程变量 entity
        // 重建 EntityContext + MDC——审计条目的实体维度即来自重建的上下文（为 null 即断链）
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(auditService.trail()).anySatisfy(entry -> {
                    assertThat(entry.action()).isEqualTo("APPROVAL_NOTIFICATION");
                    assertThat(entry.entity()).isEqualTo(EntityType.BETA);
                    assertThat(entry.detail()).contains("orderId=" + orderId);
                }));

        // 流程随通知任务完成而结束
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(runtimeService.createProcessInstanceQuery()
                        .processInstanceBusinessKey(orderId).count()).isZero());
    }

    @Test
    void actuator输出当前实体用于漂移巡检() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity").value("BETA"));
    }

    @Test
    void 查询不存在订单返回404() throws Exception {
        mockMvc.perform(get("/api/v1/orders/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 非正数订单id返回400() throws Exception {
        // @PathVariable @Positive（api-conventions）：0 与负数在控制器入口被方法校验拦截
        mockMvc.perform(get("/api/v1/orders/0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/orders/-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 超过实体配置金额上限返回400() throws Exception {
        // Schema 驱动校验（文档 5.8.3）：Beta 上限 50000 更严，95 * 600 = 57000 越界
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"widget\",\"quantity\":600}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 非法下单参数返回400() throws Exception {
        // Bean Validation 负例：与 Alpha 对称，同一契约同一约束
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"\",\"quantity\":2}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"widget\",\"quantity\":0}"))
                .andExpect(status().isBadRequest());
    }
}
