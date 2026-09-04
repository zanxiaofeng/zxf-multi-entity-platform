package com.zxf.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code RestExceptionHandler} 异常映射契约（评审修复 🔴3）：乐观锁并发冲突 → 409 可重试语义。
 * 此前缺 {@code ObjectOptimisticLockingFailureException} 映射，{@code Order}/{@code OutboxEvent}
 * 的 {@code @Version} 兜底触发时落兜底 500 + ERROR 堆栈——可重试的并发冲突被监控误报为系统故障。
 *
 * <p>放 app 模块跑<b>全量上下文</b>而非 Web 切片：异常解析链与运行期完全同构
 * （e2e 的 404/405 用例已证明 advice 在此上下文工作）。探针控制器经 @TestConfiguration
 * 注入，其余真实 bean 照常装配。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("alpha")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "alpha")
// 每测试类独立 H2 库（多上下文的 Flowable 引擎不能共享库，见 AlphaOrderApiEndToEndTest 同位置注释）
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:rest-exception-contract-db;DB_CLOSE_DELAY=-1")
class RestExceptionHandlerContractTest {

    @Autowired
    private MockMvc mockMvc;

    // 探针无真实下游依赖，mock 仅为上下文装配需要（真实 NotificationClient 需要 RestClient 配置）
    @MockitoBean
    private com.zxf.platform.core.domain.port.NotificationPort notificationPort;

    @Test
    void 乐观锁并发冲突映射为409可重试语义() throws Exception {
        mockMvc.perform(get("/probe/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("数据已被并发修改，请刷新后重试"));
    }

    @Test
    void 数据完整性冲突与乐观锁同簇映射409() throws Exception {
        // 对照组：既有 DataIntegrityViolationException 映射不因新增 handler 漂移
        mockMvc.perform(get("/probe/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void 未映射异常仍落兜底500且不回显内部细节() throws Exception {
        mockMvc.perform(get("/probe/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("服务器内部错误"));
    }

    /** 探针装配：异常注入端点（仅本测试类可见，不进入产物）。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    /** 探针控制器：按路径抛出被测异常，避免为异常映射测试引入真实持久层。 */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/optimistic-lock")
        String optimisticLock() {
            throw new ObjectOptimisticLockingFailureException(OrderProbe.class, 42L);
        }

        @GetMapping("/probe/data-integrity")
        String dataIntegrity() {
            throw new DataIntegrityViolationException("uk_orders_item 重复");
        }

        @GetMapping("/probe/unexpected")
        String unexpected() {
            throw new IllegalStateException("内部细节不得回显");
        }
    }

    /** 乐观锁异常的持久化类占位（异常构造器需要 Class 参数）。 */
    static class OrderProbe {
    }
}
