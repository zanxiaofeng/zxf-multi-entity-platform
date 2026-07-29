package com.zxf.platform.alpha.adapter;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.ForEntity;
import com.zxf.platform.core.domain.port.TaskAssignmentRule;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Alpha 审批人分配（文档 7.7.1 组件 6 示范）：按审批层级硬编码候选人。 */
@Component
@ForEntity(EntityType.ALPHA)
public class AlphaTaskAssignmentRule implements TaskAssignmentRule {

    private static final Map<String, List<String>> CANDIDATES = Map.of(
            "alphaApproveL1", List.of("alpha-manager-1"),
            "alphaApproveL2", List.of("alpha-manager-2"),
            "alphaApproveL3", List.of("alpha-director"));

    @Override
    public EntityType supports() {
        return EntityType.ALPHA;
    }

    @Override
    public List<String> candidatesFor(String taskDefinitionKey) {
        return CANDIDATES.getOrDefault(taskDefinitionKey, List.of());
    }
}
