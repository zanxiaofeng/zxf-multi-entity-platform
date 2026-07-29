package com.zxf.platform.alpha.adapter;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.domain.port.TaskAssignmentRule;
import com.zxf.platform.core.domain.port.TaskAssignmentRuleContractTest;

/**
 * Alpha 候选人策略契约测试（文档 8.4）：继承 platform-core 抽象基类，
 * 提供被测实现与期望实体即获得契约回归。
 */
class AlphaTaskAssignmentRuleContractTest extends TaskAssignmentRuleContractTest {

    @Override
    protected TaskAssignmentRule rule() {
        return new AlphaTaskAssignmentRule();
    }

    @Override
    protected EntityType expectedEntity() {
        return EntityType.ALPHA;
    }
}
