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
 * Alpha 端到端：REST → 通用服务 → 扩展点计价 → 分库持久化 → 异步审计。
 * 计价差异（13% 增值税）证明请求确实走到了 Alpha 的 SPI 实现。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("alpha")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "alpha")
class AlphaOrderApiEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditService auditService;

    @Autowired
    private RuntimeService runtimeService;

    @Test
    void 下单按Alpha计价且创建后可查询() throws Exception {
        var result = mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"widget\",\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price.amount").value(226.00)) // 200 * 1.13，Alpha 专属
                .andExpect(jsonPath("$.price.currency").value("CNY"))
                .andReturn();

        String orderId = String.valueOf((int) JsonPath.read(result.getResponse().getContentAsString(), "$.id"));

        String location = result.getResponse().getHeader("Location");
        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item").value("widget"))
                .andExpect(jsonPath("$.price.amount").value(226.00));

        // 流程拓扑差异外置（文档 7.2）：同 key 不同拓扑——Alpha 实例风控后停在 Alpha 一级审批
        var instance = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(orderId).singleResult();
        assertThat(instance).isNotNull();
        assertThat(runtimeService.getActiveActivityIds(instance.getId()))
                .containsExactly("alphaApproveL1");
        // 流程变量携带 entity 作异步 Job 线程的双保险（文档 7.3③）
        assertThat(runtimeService.getVariable(instance.getId(), "entity")).isEqualTo("ALPHA");
        assertThat(runtimeService.getVariable(instance.getId(), "orderId")).isEqualTo(Long.valueOf(orderId));

        // 异步审计：实体上下文经 TaskDecorator 传播（文档 5.2.3），实体维度写入审计记录
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(auditService.trail()).anySatisfy(entry -> {
                    assertThat(entry.action()).isEqualTo("ORDER_CREATED");
                    assertThat(entry.entity()).isEqualTo(EntityType.ALPHA);
                }));
    }

    @Test
    void actuator输出当前实体用于漂移巡检() throws Exception {
        // 文档 6.3 运行期防线：发现"A 的命名空间跑着 B 的镜像"立即告警
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity").value("ALPHA"));
    }

    @Test
    void 查询不存在订单返回404() throws Exception {
        mockMvc.perform(get("/orders/999999"))
                .andExpect(status().isNotFound());
    }
}
