package com.zxf.platform.beta.adapter;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.ForEntity;
import com.zxf.platform.core.domain.port.TaskAssignmentRule;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Beta 审批人分配（文档 7.7.1 组件 6 示范）：按审批层级硬编码候选人。 */
@Component
@ForEntity(EntityType.BETA)
public class BetaTaskAssignmentRule implements TaskAssignmentRule {

    private static final Map<String, List<String>> CANDIDATES = Map.of(
            "betaApproveL1", List.of("beta-approver-1"),
            "betaApproveL2", List.of("beta-approver-2"),
            "betaApproveL3", List.of("beta-approver-3"),
            "betaApproveL4", List.of("beta-approver-4"),
            "betaApproveL5", List.of("beta-approver-5"));

    @Override
    public EntityType supports() {
        return EntityType.BETA;
    }

    @Override
    public List<String> candidatesFor(String taskDefinitionKey) {
        return CANDIDATES.getOrDefault(taskDefinitionKey, List.of());
    }
}
