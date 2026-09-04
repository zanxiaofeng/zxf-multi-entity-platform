package com.zxf.platform;

import java.util.List;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Beta 流程装配冒烟（文档 7.4）：共享断言继承 {@link AbstractProcessAssemblySmokeTest}，
 * 本类只声明 Beta 拓扑——五级审批 + 专属审计留痕 + 通知。
 */
@ActiveProfiles("beta")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "beta")
// 每测试类独立 H2 库：原因见 AlphaOrderApiEndToEndTest 同位置注释
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:beta-process-smoke-db;DB_CLOSE_DELAY=-1")
class BetaProcessAssemblySmokeTest extends AbstractProcessAssemblySmokeTest {

    @Override
    protected List<String> expectedUserTaskIds() {
        return List.of("betaApproveL1", "betaApproveL2", "betaApproveL3", "betaApproveL4", "betaApproveL5");
    }

    @Override
    protected List<String> expectedServiceTaskIds() {
        return List.of("betaAuditRecord", "sendNotification");
    }
}
