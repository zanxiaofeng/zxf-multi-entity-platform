package com.zxf.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.domain.model.NotificationFailedException;
import com.zxf.platform.core.domain.port.NotificationPort;
import com.zxf.platform.core.domain.port.OutboxRepository;
import com.zxf.platform.core.infrastructure.engine.DeadLetterJobOperations;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Beta 端到端：与 Alpha 对称，计价差异（95 折）证明请求走到了 Beta 的 SPI 实现。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("beta")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "beta")
// 每测试类独立 H2 库：原因见 AlphaOrderApiEndToEndTest 同位置注释
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:beta-e2e-db;DB_CLOSE_DELAY=-1")
class BetaOrderApiEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditService auditService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private OutboxRepository outboxRepository;

    /**
     * 组件 11（文档 7.7.2）：SendNotificationDelegate 现在经 NotificationPort 调真实下游。
     * e2e 默认 doNothing——正常路径下通知静默成功，断言逻辑保持不变。
     * 死信路径在 dedicated 测试中用 {@code doThrow(...)} stub。
     */
    @MockitoBean
    private NotificationPort notificationPort;

    @Autowired
    private DeadLetterJobOperations deadLetterJobOperations;

    @Test
    void 下单按Beta计价且创建后可查询() throws Exception {
        var result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"widget\",\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price.amount").value(190.00)) // 200 * 0.95，Beta 专属
                .andExpect(jsonPath("$.price.currency").value("CNY"))
                .andExpect(jsonPath("$.status").value("CREATED")) // Beta 无风控节点，恒初始态（M3 对照）
                .andReturn();

        // 金额序列化形态守护：190.00 经 Money 归一化为 scale=-1（toString 是 1.9E+2），
        // write-bigdecimal-as-plain 保证对外恒为十进制——该配置回退时 190 侧最先暴露
        assertThat(result.getResponse().getContentAsString()).doesNotContain("E+");

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
    void 审批任务创建后自动分配候选人() throws Exception {
        // 候选人策略（文档 7.7.1 组件 6）：TASK_CREATED 时 TaskAssignmentListener 调用 BetaTaskAssignmentRule
        // 为 betaApproveL1 写入候选人 beta-approver-1——与 Alpha 对称
        var result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"widget\",\"quantity\":1}"))
                .andExpect(status().isCreated())
                .andReturn();
        String orderId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

        var instance = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(orderId).singleResult();
        var task = taskService.createTaskQuery()
                .processInstanceId(instance.getId()).active().singleResult();

        var links = taskService.getIdentityLinksForTask(task.getId());
        assertThat(links)
                .anyMatch(link -> "candidate".equals(link.getType())
                        && "beta-approver-1".equals(link.getUserId()));
    }

    @Test
    void 通知持续失败耗尽重试进死信且复活后流程走完() throws Exception {
        // 组件 4 全链路（文档 7.7.1）：与 Alpha 对称——技术异常 → R3/PT5S 耗尽
        // → 死信 → 复活 → 流程走完
        doThrow(new NotificationFailedException("模拟通知下游持续故障", new RuntimeException("下游不可用")))
                .when(notificationPort).send(anyString(), anyString());
        try {
            var result = mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"item\":\"gadget\",\"quantity\":1}"))
                    .andExpect(status().isCreated())
                    .andReturn();
            String orderId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
            var instance = runtimeService.createProcessInstanceQuery()
                    .processInstanceBusinessKey(orderId).singleResult();
            assertThat(instance).isNotNull();

            // 走完五级审批 → async sendNotification 每次执行都抛 NotificationFailedException
            Task task;
            while ((task = taskService.createTaskQuery().processInstanceId(instance.getId()).active().singleResult()) != null) {
                taskService.complete(task.getId());
            }

            // R3/PT5S 重试耗尽（约 15s + Job acquisition 轮询，预算放宽到 90s）后进死信表
            await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                    assertThat(deadLetterJobOperations.list())
                            .anyMatch(job -> instance.getId().equals(job.processInstanceId())));

            // 下游恢复（reset 解除 stub）→ 复活死信 Job → 通知成功、流程走完
            reset(notificationPort);
            var deadLetter = deadLetterJobOperations.list().stream()
                    .filter(job -> instance.getId().equals(job.processInstanceId()))
                    .findFirst().orElseThrow();
            deadLetterJobOperations.retry(deadLetter.jobId());

            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertThat(runtimeService.createProcessInstanceQuery()
                        .processInstanceBusinessKey(orderId).count()).isZero();
                // 死信已随复活执行清空（本实例）
                assertThat(deadLetterJobOperations.list())
                        .noneMatch(job -> instance.getId().equals(job.processInstanceId()));
            });
        } finally {
            // 兜底解除 stub：即使中途失败也不污染同类的其它测试
            reset(notificationPort);
        }
    }

    @Test
    void 下单后outbox事件被relay发布() throws Exception {
        // Transactional Outbox（文档 7.7.2 组件 12，core 共用逻辑）：与 Alpha 侧对称覆盖——
        // 两产物互不背书，beta 装配下 outbox 写入/relay 标记同样需要自有回归
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"widget\",\"quantity\":1}"))
                .andExpect(status().isCreated());

        // relay fixedDelay=5s，等 relay 扫描并发布（findUnpublished 返回空即已全部标记）
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(outboxRepository.findUnpublished(10)).isEmpty());
    }

    @Test
    void actuator输出当前实体用于漂移巡检() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity").value("BETA"));
    }

    @Test
    void actuatorHealth包含Flowable健康检查() throws Exception {
        // 文档 7.7.2 组件 14：与 Alpha 侧对称——platform-flowable-starter 在 beta 产物上
        // 同样装配 FlowableEngineHealthIndicator（@ConditionalOnClass + AutoConfiguration.imports）
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.flowable").exists());
    }

    @Test
    void 查询不存在订单返回404() throws Exception {
        // 领域异常（exception-handling §3.1）：与 Alpha 同一契约——code 属性钉住 CODE
        mockMvc.perform(get("/api/v1/orders/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
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
    void 非数字订单id返回400() throws Exception {
        // MethodArgumentTypeMismatchException → 400（exception-handling §6.2），不得回落兜底 500
        mockMvc.perform(get("/api/v1/orders/abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 不支持的HTTP方法返回405() throws Exception {
        // HttpRequestMethodNotSupportedException → 405（exception-handling §6.2 矩阵）：
        // advice 兜底 handler 会吞掉未显式声明的协议异常，本用例防 405 回落成 500
        mockMvc.perform(delete("/api/v1/orders/1"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void 不支持的ContentType返回415() throws Exception {
        // HttpMediaTypeNotSupportedException → 415（exception-handling §6.2 矩阵）：同上防回落 500
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not-json"))
                .andExpect(status().isUnsupportedMediaType());
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
