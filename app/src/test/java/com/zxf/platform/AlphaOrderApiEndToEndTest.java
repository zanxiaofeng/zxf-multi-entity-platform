package com.zxf.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.domain.port.NotificationPort;
import com.zxf.platform.core.domain.port.OutboxRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    @Test
    void 下单按Alpha计价且创建后可查询() throws Exception {
        var result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"widget\",\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price.amount").value(226.00)) // 200 * 1.13，Alpha 专属
                .andExpect(jsonPath("$.price.currency").value("CNY"))
                .andReturn();

        String orderId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

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
        assertThat(runtimeService.getVariable(instance.getId(), "orderId")).isEqualTo(orderId);

        // 异步审计：AFTER_COMMIT 事件 + @Async 监听器，实体上下文经 TaskDecorator 传播（文档 5.2.3 / 8.1 规则 11）
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(auditService.trail()).anySatisfy(entry -> {
                    assertThat(entry.action()).isEqualTo("ORDER_CREATED");
                    assertThat(entry.entity()).isEqualTo(EntityType.ALPHA);
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

        // 走完三级审批 → sendNotification 是 async 节点，由引擎 Job 执行器线程运行
        Task task;
        while ((task = taskService.createTaskQuery().processInstanceId(instance.getId()).active().singleResult()) != null) {
            taskService.complete(task.getId());
        }

        // 双保险闭环（文档 7.3③）：Job 线程无请求上下文，delegate 基类从流程变量 entity
        // 重建 EntityContext + MDC——审计条目的实体维度即来自重建的上下文（为 null 即断链）
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(auditService.trail()).anySatisfy(entry -> {
                    assertThat(entry.action()).isEqualTo("APPROVAL_NOTIFICATION");
                    assertThat(entry.entity()).isEqualTo(EntityType.ALPHA);
                    assertThat(entry.detail()).contains("orderId=" + orderId);
                }));

        // 流程随通知任务完成而结束
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(runtimeService.createProcessInstanceQuery()
                        .processInstanceBusinessKey(orderId).count()).isZero());
    }

    @Test
    void 审批任务创建后自动分配候选人() throws Exception {
        // 候选人策略（文档 7.7.1 组件 6）：TASK_CREATED 时 TaskAssignmentListener 调用 AlphaTaskAssignmentRule
        // 为 alphaApproveL1 写入候选人 alpha-manager-1——替代 ActivityBehaviorFactory 的更简洁示范
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
                        && "alpha-manager-1".equals(link.getUserId()));
    }

    @Test
    void 下单后outbox事件被relay发布() throws Exception {
        // Transactional Outbox（文档 7.7.2 组件 12）：OrderApplicationService.create 在事务内
        // 写 outbox_event，与 orders 表同事务提交；OutboxRelay 每 5s 扫描未发布事件并标记。
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
        // 文档 6.3 运行期防线：发现"A 的命名空间跑着 B 的镜像"立即告警
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity").value("ALPHA"));
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
        // Schema 驱动校验（文档 5.8.3）：Alpha 上限 100000，113 * 1000 = 113000 越界——
        // 管道在定价之后运行，IllegalArgumentException 经 RestExceptionHandler 映射 400
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"widget\",\"quantity\":1000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 非法下单参数返回400() throws Exception {
        // Bean Validation 负例：空 item、非正 quantity 均应被 @Valid 拦截在控制器入口
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
