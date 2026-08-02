package com.zxf.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.infrastructure.engine.OrderApprovalService;
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
import org.springframework.test.context.TestPropertySource;

/**
 * Alpha 流程装配冒烟（文档 7.4）：Flowable 引擎<b>不做启动期 delegate 校验</b>——
 * {@code delegateExpression} 在活动执行时才求值，缺 bean 时流程运行到该任务才抛
 * {@code FlowableObjectNotFoundException}，是在途实例级事故。以下自研校验与
 * 5.2.5 PolicyRegistry 的 fail-fast 同等级。
 */
@SpringBootTest
@ActiveProfiles("alpha")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "alpha")
// 每测试类独立 H2 库：原因见 AlphaOrderApiEndToEndTest 同位置注释
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:alpha-process-smoke-db;DB_CLOSE_DELAY=-1")
class AlphaProcessAssemblySmokeTest {

    /** 匹配 {@code ${beanName}} 形式的委托表达式（类级缓存，避免每次调用重新编译）。 */
    private static final Pattern DELEGATE_EXPRESSION_PATTERN = Pattern.compile("^\\$\\{(.+)}$");

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private ApplicationContext context;

    @Test
    void 同一流程key只有一份部署定义() {
        // 防双 BPMN 静默错路由：裁剪失效导致两个实体模块同时进 classpath 时，
        // 同 key 两份定义会以 v1/v2 共存，startProcessInstanceByKey 静默路由到最新版本（文档 7.2）
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
    void Alpha拓扑为风控加三级审批() {
        var definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(OrderApprovalService.ORDER_APPROVAL_KEY).singleResult();
        var model = repositoryService.getBpmnModel(definition.getId());

        assertThat(model.getMainProcess().findFlowElementsOfType(UserTask.class))
                .extracting(FlowElement::getId)
                .containsExactlyInAnyOrder("alphaApproveL1", "alphaApproveL2", "alphaApproveL3");
        assertThat(model.getMainProcess().findFlowElementsOfType(ServiceTask.class))
                .extracting(FlowElement::getId)
                .containsExactlyInAnyOrder("alphaRiskCheck", "sendNotification");
    }

    /** 提取 {@code ${beanName}} 形式的委托表达式中的 bean 名；非委托表达式返回 null。 */
    private static @Nullable String extractDelegateBeanName(@Nullable String implementation) {
        if (implementation == null) {
            return null;
        }
        var matcher = DELEGATE_EXPRESSION_PATTERN.matcher(implementation);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
