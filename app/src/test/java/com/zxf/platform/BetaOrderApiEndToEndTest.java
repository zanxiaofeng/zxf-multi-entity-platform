package com.zxf.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zxf.platform.core.audit.AuditService;
import com.zxf.platform.core.context.EntityType;
import java.time.Duration;
import org.flowable.engine.RuntimeService;
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

    @Test
    void 下单按Beta计价且创建后可查询() throws Exception {
        var result = mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"widget\",\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price.amount").value(190.00)) // 200 * 0.95，Beta 专属
                .andExpect(jsonPath("$.price.currency").value("CNY"))
                .andReturn();

        String orderId = String.valueOf((int) JsonPath.read(result.getResponse().getContentAsString(), "$.id"));

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
        assertThat(runtimeService.getVariable(instance.getId(), "orderId")).isEqualTo(Long.valueOf(orderId));

        // 异步审计：实体上下文经 TaskDecorator 传播（文档 5.2.3）
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(auditService.trail()).anySatisfy(entry -> {
                    assertThat(entry.action()).isEqualTo("ORDER_CREATED");
                    assertThat(entry.entity()).isEqualTo(EntityType.BETA);
                }));
    }

    @Test
    void actuator输出当前实体用于漂移巡检() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity").value("BETA"));
    }

    @Test
    void 查询不存在订单返回404() throws Exception {
        mockMvc.perform(get("/orders/999999"))
                .andExpect(status().isNotFound());
    }
}
