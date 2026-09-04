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
import java.math.BigDecimal;
import java.time.Duration;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 订单 e2e 基类：REST → 通用服务 → 扩展点计价 → 分库持久化 → 流程引擎 → 异步审计的
 * 全链路断言。两实体共享的契约用例（下单模板、审批全链路、死信复活、outbox、actuator
 * 巡检、协议负例 400/404/405/415）上移至此——它们验证的是 core 共享代码与两产物各自
 * 的装配，实体差异以抽象方法声明；实体特有的分支用例（如 Alpha 风控拒绝）留在子类。
 *
 * <p>子类标注 {@code @ActiveProfiles} / {@code @EnabledIfSystemProperty} / 独立 H2 库的
 * {@code @TestPropertySource}（实体特定值无法参数化上移）。
 *
 * <p>{@code @MockitoBean} 支持超类字段：{@link NotificationPort} 在基类统一 mock——
 * e2e 默认 doNothing（正常路径通知静默成功），死信路径用例内 doThrow 后 finally reset。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractOrderApiEndToEndTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected AuditService auditService;

    @Autowired
    protected RuntimeService runtimeService;

    @Autowired
    protected TaskService taskService;

    @Autowired
    protected OutboxRepository outboxRepository;

    @Autowired
    protected DeadLetterJobOperations deadLetterJobOperations;

    @MockitoBean
    protected NotificationPort notificationPort;

    /** 当前装配实体（差异点：审计断言、流程变量、actuator 巡检值）。 */
    protected abstract EntityType entityType();

    /** quantity=2 的期望计价（差异点：Alpha 200×1.13=226.00 / Beta 200×0.95=190.00）。 */
    protected abstract BigDecimal expectedPrice();

    /** 下单后流程停驻的首个活动节点（差异点：Alpha 风控后 L1 / Beta 直接 L1）。 */
    protected abstract String expectedFirstActivityId();

    /** 首个审批任务的候选人（差异点：实体各自的 TaskAssignmentRule）。 */
    protected abstract String expectedFirstCandidate();

    /** 超过本实体 Schema 金额上限的下单数量（差异点：Alpha 上限 100000 / Beta 50000）。 */
    protected abstract int overLimitQuantity();

    @Test
    void 下单按实体计价且创建后可查询() throws Exception {
        var result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"widget\",\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.price.amount").value(expectedPrice().doubleValue()))
                .andExpect(jsonPath("$.price.currency").value("CNY"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn();

        // 金额序列化形态守护：write-bigdecimal-as-plain 回退时科学计数法（如 Beta 的 1.9E+2）
        // 会进入对外 JSON——两侧 e2e 各自断言，配置漂移即时暴露
        assertThat(result.getResponse().getContentAsString()).doesNotContain("E+");

        String orderId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

        String location = result.getResponse().getHeader("Location");
        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.item").value("widget"))
                .andExpect(jsonPath("$.price.amount").value(expectedPrice().doubleValue()));

        // 流程拓扑差异外置（文档 7.2）：同 key 不同拓扑——实例停驻在实体声明的首个审批节点
        var instance = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(orderId).singleResult();
        assertThat(instance).isNotNull();
        assertThat(runtimeService.getActiveActivityIds(instance.getId()))
                .containsExactly(expectedFirstActivityId());
        // 流程变量携带 entity 作异步 Job 线程的双保险（文档 7.3③）
        assertThat(runtimeService.getVariable(instance.getId(), "entity")).isEqualTo(entityType().name());
        assertThat(runtimeService.getVariable(instance.getId(), "orderId")).isEqualTo(orderId);

        // 异步审计：AFTER_COMMIT 事件 + @Async 监听器，实体上下文经 TaskDecorator 传播（文档 5.2.3 / 8.1 规则 11）
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(auditService.trail()).anySatisfy(entry -> {
                    assertThat(entry.action()).isEqualTo("ORDER_CREATED");
                    assertThat(entry.entity()).isEqualTo(entityType());
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

        // 走完全部审批 → sendNotification 是 async 节点，由引擎 Job 执行器线程运行
        completeAllActiveTasks(instance.getId());

        // 双保险闭环（文档 7.3③）：Job 线程无请求上下文，delegate 基类从流程变量 entity
        // 重建 EntityContext + MDC——审计条目的实体维度即来自重建的上下文（为 null 即断链）
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(auditService.trail()).anySatisfy(entry -> {
                    assertThat(entry.action()).isEqualTo("APPROVAL_NOTIFICATION");
                    assertThat(entry.entity()).isEqualTo(entityType());
                    assertThat(entry.detail()).contains("orderId=" + orderId);
                }));

        // 流程随通知任务完成而结束
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(runtimeService.createProcessInstanceQuery()
                        .processInstanceBusinessKey(orderId).count()).isZero());
    }

    @Test
    void 审批任务创建后自动分配候选人() throws Exception {
        // 候选人策略（文档 7.7.1 组件 6）：TASK_CREATED 时 TaskAssignmentListener 调用实体
        // TaskAssignmentRule 为首个审批任务写入候选人——替代 ActivityBehaviorFactory 的更简洁示范
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
                        && expectedFirstCandidate().equals(link.getUserId()));
    }

    @Test
    void 通知持续失败耗尽重试进死信且复活后流程走完() throws Exception {
        // 组件 4 全链路（文档 7.7.1）：技术异常 → failedJobRetryTimeCycle R3/PT5S 耗尽
        // → ACT_RU_DEADLETTER_JOB 死信 → 运维复活 → 流程走完。
        // 与组件 5 对照：BpmnError 走分支不重试，技术异常走重试→死信
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

            // 走完全部审批 → async sendNotification 每次执行都抛 NotificationFailedException
            completeAllActiveTasks(instance.getId());

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
        // Transactional Outbox（文档 7.7.2 组件 12）：OrderApplicationService.create 在事务内
        // 写 outbox_event，与 orders 表同事务提交；OutboxRelay 每 5s 扫描未发布事件并标记。
        // 两产物互不背书：各自装配下 outbox 写入/relay 标记同样需要自有回归
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
                .andExpect(jsonPath("$.entity").value(entityType().name()));
    }

    @Test
    void actuatorHealth包含Flowable健康检查() throws Exception {
        // 文档 7.7.2 组件 14：platform-flowable-starter 装配 FlowableEngineHealthIndicator，
        // /actuator/health 自动出现 flowable 组件（@ConditionalOnClass + AutoConfiguration.imports 激活）
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.flowable").exists());
    }

    @Test
    void 查询不存在订单返回404() throws Exception {
        // 领域异常（exception-handling §3.1）：OrderNotFoundException 映射 404，
        // ProblemDetail 的 code 属性暴露稳定契约 CODE——e2e 钉住 CODE 不漂移
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
        // Schema 驱动校验（文档 5.8.3）：管道在定价之后运行，RuleViolationException 经
        // RestExceptionHandler 映射 400——上限值由 per-entity yaml 声明，越界数量子类给出
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"widget\",\"quantity\":" + overLimitQuantity() + "}"))
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

    /** 逐个完成流程实例当前活动的全部任务（审批层级差异由此对子类透明）。 */
    private void completeAllActiveTasks(String processInstanceId) {
        Task task;
        while ((task = taskService.createTaskQuery().processInstanceId(processInstanceId).active().singleResult()) != null) {
            taskService.complete(task.getId());
        }
    }
}
