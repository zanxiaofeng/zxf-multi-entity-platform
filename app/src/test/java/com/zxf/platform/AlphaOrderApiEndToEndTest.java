package com.zxf.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.infrastructure.engine.DeadLetterJobOperations;
import java.math.BigDecimal;
import java.time.Duration;
import org.flowable.engine.HistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Alpha 端到端：共享契约用例继承 {@link AbstractOrderApiEndToEndTest}，本类声明 Alpha
 * 差异（13% 增值税计价、风控后停驻 L1、专属候选人、Schema 上限）并保留 Alpha 特有的
 * 风控拒绝分支用例。计价差异证明请求确实走到了 Alpha 的 SPI 实现。
 */
@ActiveProfiles("alpha")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "alpha")
// 每测试类独立 H2 库：Spring 上下文缓存会让多个测试上下文的 Flowable 引擎同时存活，
// 共享库（yaml 默认 alpha-db）下引擎跨上下文抢 Job——mock 隔离失效且重试/死信时序不确定
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:alpha-e2e-db;DB_CLOSE_DELAY=-1")
class AlphaOrderApiEndToEndTest extends AbstractOrderApiEndToEndTest {

    @Autowired
    private HistoryService historyService;

    @Autowired
    private DeadLetterJobOperations deadLetterJobOperations;

    @Override
    protected EntityType entityType() {
        return EntityType.ALPHA;
    }

    @Override
    protected BigDecimal expectedPrice() {
        return new BigDecimal("226.00"); // 200 * 1.13，Alpha 专属
    }

    @Override
    protected String expectedFirstActivityId() {
        return "alphaApproveL1"; // 风控（同步）通过后停驻一级审批
    }

    @Override
    protected String expectedFirstCandidate() {
        return "alpha-manager-1";
    }

    @Override
    protected int overLimitQuantity() {
        return 1000; // Alpha 上限 100000，113 * 1000 = 113000 越界
    }

    @Test
    void 风控命中黑名单走BpmnError分支且不产生死信() throws Exception {
        // 组件 5（文档 7.7.1）：业务错误（BpmnError RISK_REJECTED）由边界错误事件捕获走
        // "风控拒绝"分支——不触发 Job 重试、不产生死信（与技术异常 failedJobRetryTimeCycle 对照）。
        // 触发条件请求可控：item 以 "risk-" 开头（AlphaRiskCheckDelegate.RISK_ITEM_PREFIX）
        var result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"risk-widget\",\"quantity\":1}"))
                .andExpect(status().isCreated())
                // 评审修复 M3 方案 b：拒绝订单行保留（201 可查），但状态显式落账 RISK_REJECTED
                .andExpect(jsonPath("$.status").value("RISK_REJECTED"))
                .andReturn();
        String orderId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

        // 风控是同步节点：下单返回时流程已被边界事件捕获并走到"风控拒绝"终态——
        // 订单照常创建（201），但运行中实例归零（历史表留痕）
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(orderId).count()).isZero();
        var historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(orderId).singleResult();
        assertThat(historic).isNotNull();
        assertThat(historic.getEndTime()).isNotNull();

        // 查询侧同样可见终态（status 列经 riskRejectTask delegate 落库）
        mockMvc.perform(get("/api/v1/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RISK_REJECTED"));

        // 审计携带终态：OrderCreatedEvent 的 status 组件（AFTER_COMMIT + @Async，与既有审计断言同模式）
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(auditService.trail()).anySatisfy(entry -> {
                    assertThat(entry.action()).isEqualTo("ORDER_CREATED");
                    assertThat(entry.detail()).contains("orderId=" + orderId);
                    assertThat(entry.detail()).contains("status=RISK_REJECTED");
                }));

        // BpmnError 走分支不重试：本实例不产生死信 Job
        assertThat(deadLetterJobOperations.list())
                .noneMatch(job -> historic.getId().equals(job.processInstanceId()));
    }
}
