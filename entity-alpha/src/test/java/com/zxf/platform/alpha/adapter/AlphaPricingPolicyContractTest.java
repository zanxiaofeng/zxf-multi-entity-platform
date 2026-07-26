package com.zxf.platform.alpha.adapter;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.domain.port.PricingPolicy;
import com.zxf.platform.core.domain.port.PricingPolicyContractTest;

/**
 * Alpha 计价策略契约测试（文档 8.4）：继承 platform-core 抽象基类，
 * 提供被测实现与期望实体即获得契约回归——新增实体零编写契约用例。
 */
class AlphaPricingPolicyContractTest extends PricingPolicyContractTest {

    @Override
    protected PricingPolicy policy() {
        return new AlphaPricingPolicy();
    }

    @Override
    protected EntityType expectedEntity() {
        return EntityType.ALPHA;
    }
}
