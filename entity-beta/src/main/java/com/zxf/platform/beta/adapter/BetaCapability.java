package com.zxf.platform.beta.adapter;

import com.zxf.platform.core.context.EntityCapability;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.ForEntity;
import com.zxf.platform.core.domain.port.PricingPolicy;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Beta 实体能力自描述（文档 5.10.2 已落地）：随 {@code @ForEntity(BETA)} 激活，
 * core 启动时汇入 {@code List<EntityCapability>}，输出到 {@code /actuator/info}。
 *
 * <p>与 {@code AlphaCapability} 结构完全对称——demo 演示两实体的能力清单同形态。
 */
@Component
@ForEntity(EntityType.BETA)
public class BetaCapability implements EntityCapability {

    @Override
    public EntityType entity() {
        return EntityType.BETA;
    }

    @Override
    public Set<Class<?>> providedPolicies() {
        return Set.of(PricingPolicy.class);
    }

    @Override
    public String moduleVersion() {
        return "1.0.0-SNAPSHOT";
    }
}
