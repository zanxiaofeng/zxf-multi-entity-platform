package com.zxf.platform;

import java.util.List;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Alpha 流程装配冒烟（文档 7.4）：共享断言继承 {@link AbstractProcessAssemblySmokeTest}，
 * 本类只声明 Alpha 拓扑——风控检查 + 错误边界拒绝落账 + 三级审批 + 通知。
 */
@ActiveProfiles("alpha")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "alpha")
// 每测试类独立 H2 库：原因见 AlphaOrderApiEndToEndTest 同位置注释
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:alpha-process-smoke-db;DB_CLOSE_DELAY=-1")
class AlphaProcessAssemblySmokeTest extends AbstractProcessAssemblySmokeTest {

    @Override
    protected List<String> expectedUserTaskIds() {
        return List.of("alphaApproveL1", "alphaApproveL2", "alphaApproveL3");
    }

    @Override
    protected List<String> expectedServiceTaskIds() {
        return List.of("alphaRiskCheck", "riskRejectTask", "sendNotification");
    }
}
