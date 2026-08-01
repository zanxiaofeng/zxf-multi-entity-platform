package com.zxf.platform.core.domain.port;

import com.zxf.platform.core.context.EntityType;
import java.util.List;

/**
 * 审批人分配策略（文档 7.7.1 组件 6）：按任务定义键返回候选人列表。
 *
 * <p>与 {@link PricingPolicy} 同套 SPI：实现类必须 {@code @ForEntity} 限定，
 * {@code supports()} 返回值与注解一致（契约测试守护）。
 * 文档建议的 {@code ActivityBehaviorFactory} 为替代方案——示范级用全局事件监听器更简洁。
 */
public interface TaskAssignmentRule {

    EntityType supports();

    List<String> candidatesFor(String taskDefinitionKey);
}
