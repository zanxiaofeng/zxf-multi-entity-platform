package com.zxf.platform.core.domain.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.ForEntity;
import org.junit.jupiter.api.Test;

/**
 * 候选人策略契约测试基类（文档 8.4）：与 {@link PricingPolicyContractTest} 同构。
 * platform-core test-jar 发布，实体模块继承即获得契约回归。
 */
public abstract class TaskAssignmentRuleContractTest {

    protected abstract TaskAssignmentRule rule();

    protected abstract EntityType expectedEntity();

    @Test
    void supports与所在模块实体一致() {
        assertThat(rule().supports()).isEqualTo(expectedEntity());
    }

    @Test
    void 激活注解声明的实体与supports一致() {
        var annotation = rule().getClass().getAnnotation(ForEntity.class);
        assertThat(annotation)
                .as("@ForEntity 必须标注，且 value 与 supports() 一致")
                .isNotNull();
        assertThat(annotation.value()).isEqualTo(rule().supports());
    }
}
