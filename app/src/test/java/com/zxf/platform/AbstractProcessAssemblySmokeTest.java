package com.zxf.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.infrastructure.engine.OrderApprovalService;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 流程装配冒烟基类（文档 7.4）：两侧装配共享的断言上移至此，实体差异（期望节点集合）
 * 由子类以 {@link #expectedUserTaskIds()} / {@link #expectedServiceTaskIds()} 声明。
 *
 * <p>Flowable 引擎<b>不做启动期 delegate 校验</b>——{@code delegateExpression} 在活动执行时
 * 才求值，缺 bean 时流程运行到该任务才抛 {@code FlowableObjectNotFoundException}，是在途
 * 实例级事故。以下自研校验与 5.2.5 PolicyRegistry 的 fail-fast 同等级。
 *
 * <p>子类标注 {@code @ActiveProfiles} / {@code @EnabledIfSystemProperty} / 独立 H2 库的
 * {@code @TestPropertySource}（实体特定值无法参数化上移）。
 */
@SpringBootTest
abstract class AbstractProcessAssemblySmokeTest {

    /** 匹配 {@code ${beanName}} 形式的委托表达式（类级缓存，避免每次调用重新编译）。 */
    private static final Pattern DELEGATE_EXPRESSION_PATTERN = Pattern.compile("^\\$\\{(.+)}$");

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private ApplicationContext context;

    /** 期望的审批节点 id 集合（实体差异点：Alpha 三级 / Beta 五级）。 */
    protected abstract List<String> expectedUserTaskIds();

    /** 期望的服务任务节点 id 集合（实体差异点：Alpha 风控+拒绝落账+通知 / Beta 审计+通知）。 */
    protected abstract List<String> expectedServiceTaskIds();

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
    void 流程拓扑符合实体声明() {
        var definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(OrderApprovalService.ORDER_APPROVAL_KEY).singleResult();
        var model = repositoryService.getBpmnModel(definition.getId());

        assertThat(model.getMainProcess().findFlowElementsOfType(UserTask.class))
                .extracting(FlowElement::getId)
                .containsExactlyInAnyOrderElementsOf(expectedUserTaskIds());
        assertThat(model.getMainProcess().findFlowElementsOfType(ServiceTask.class))
                .extracting(FlowElement::getId)
                .containsExactlyInAnyOrderElementsOf(expectedServiceTaskIds());
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
