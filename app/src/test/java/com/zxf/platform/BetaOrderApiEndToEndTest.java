package com.zxf.platform;

import com.zxf.platform.core.context.EntityType;
import java.math.BigDecimal;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Beta 端到端：共享契约用例继承 {@link AbstractOrderApiEndToEndTest}，本类只声明 Beta
 * 差异（95 折计价、无风控直接停驻 L1、专属候选人、更严 Schema 上限）。计价差异
 * 证明请求走到了 Beta 的 SPI 实现；Beta 无风控节点，状态恒为初始态 CREATED。
 */
@ActiveProfiles("beta")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "beta")
// 每测试类独立 H2 库：原因见 AlphaOrderApiEndToEndTest 同位置注释
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:beta-e2e-db;DB_CLOSE_DELAY=-1")
class BetaOrderApiEndToEndTest extends AbstractOrderApiEndToEndTest {

    @Override
    protected EntityType entityType() {
        return EntityType.BETA;
    }

    @Override
    protected BigDecimal expectedPrice() {
        return new BigDecimal("190.00"); // 200 * 0.95，Beta 专属
    }

    @Override
    protected String expectedFirstActivityId() {
        return "betaApproveL1"; // Beta 无风控节点，直接停驻一级审批
    }

    @Override
    protected String expectedFirstCandidate() {
        return "beta-approver-1";
    }

    @Override
    protected int overLimitQuantity() {
        return 600; // Beta 上限 50000 更严，95 * 600 = 57000 越界
    }
}
