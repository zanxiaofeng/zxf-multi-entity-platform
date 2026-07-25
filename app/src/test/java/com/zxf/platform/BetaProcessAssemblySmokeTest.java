package com.zxf.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.flow.OrderApprovalService;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * Beta 流程装配冒烟（文档 7.4）：与 Alpha 对称——同 key 定义唯一、delegate 全装配、
 * Beta 拓扑为五级审批 + 专属审计留痕。
 */
@SpringBootTest
@ActiveProfiles("beta")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "beta")
class BetaProcessAssemblySmokeTest {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private ApplicationContext context;

    @Test
    void 同一流程key只有一份部署定义() {
        assertThat(repositoryService.createProcessDefinitionQuery()
                        .processDefinitionKey(OrderApprovalService.ORDER_APPROVAL_KEY).list())
                .hasSize(1);
    }

    @Test
    void 所有BPMN引用的delegate均已装配() {
        var missing = new ArrayList<String>();
        repositoryService.createProcessDefinitionQuery().list().forEach(definition -> {
            var model = repositoryService.getBpmnModel(definition.getId());
            model.getMainProcess().findFlowElementsOfType(ServiceTask.class).forEach(task -> {
                String beanName = extractDelegateBeanName(task.getImplementation());
                if (beanName != null && !context.containsBean(beanName)) {
                    missing.add(definition.getKey() + ":" + task.getId() + " -> ${" + beanName + "}");
                }
            });
        });
        assertThat(missing).as("BPMN 引用了未装配的 delegate bean").isEmpty();
    }

    @Test
    void Beta拓扑为五级审批加审计留痕() {
        var definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(OrderApprovalService.ORDER_APPROVAL_KEY).singleResult();
        var model = repositoryService.getBpmnModel(definition.getId());

        assertThat(model.getMainProcess().findFlowElementsOfType(UserTask.class))
                .extracting(FlowElement::getId)
                .containsExactlyInAnyOrder(
                        "betaApproveL1", "betaApproveL2", "betaApproveL3", "betaApproveL4", "betaApproveL5");
        assertThat(model.getMainProcess().findFlowElementsOfType(ServiceTask.class))
                .extracting(FlowElement::getId)
                .containsExactlyInAnyOrder("betaAuditRecord", "sendNotification");
    }

    /** 提取 {@code ${beanName}} 形式的委托表达式中的 bean 名；非委托表达式返回 null。 */
    private static @Nullable String extractDelegateBeanName(@Nullable String implementation) {
        if (implementation == null) {
            return null;
        }
        var matcher = Pattern.compile("^\\$\\{(.+)}$").matcher(implementation);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
