package com.zxf.platform.alpha.adapter;

import com.zxf.platform.core.context.EntityCapability;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.ForEntity;
import com.zxf.platform.core.domain.port.PricingPolicy;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Alpha 实体能力自描述（文档 5.10.2 已落地）：随 {@code @ForEntity(ALPHA)} 激活，
 * core 启动时汇入 {@code List<EntityCapability>}，输出到 {@code /actuator/info}。
 *
 * <p>{@code providedPolicies()} 仅列 {@code PricingPolicy}——当前 Alpha 实际提供的
 * 扩展点（其他扩展点如 OrderValidator 见 6.4 / 后续章节，按工程实际补齐）。
 */
@Component
@ForEntity(EntityType.ALPHA)
public class AlphaCapability implements EntityCapability {

    @Override
    public EntityType entity() {
        return EntityType.ALPHA;
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
