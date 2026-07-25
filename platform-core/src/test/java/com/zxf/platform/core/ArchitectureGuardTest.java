package com.zxf.platform.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;

/**
 * 架构守护（文档 7.1.1 / 7.3）：核心层全文检索 {@code EntityType} / {@code platform.entity}，
 * 只允许出现在白名单包中——业务服务、订单领域、Web 控制器里出现即打回（含未来新增包，
 * 默认拒绝）。
 */
@AnalyzeClasses(packages = "com.zxf.platform.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGuardTest {

    /**
     * 允许感知实体的白名单包：context（上下文自身）、policy（注册表按实体路由）、
     * flow（引擎适配层，与 Filter 同级豁免，文档 7.3）、audit（审计打实体维度）、
     * config（上下文传播装配）。其余 core 包（service / order / 未来新增）一律禁止。
     */
    private static final String[] ENTITY_AWARE_PACKAGES = {
            "..core.context..", "..core.policy..", "..core.flow..", "..core.audit..", "..core.config.."};

    @ArchTest
    static final ArchRule 白名单外核心代码不得感知实体枚举 = noClasses()
            .that().resideInAPackage("..core..")
            .and().resideOutsideOfPackages(ENTITY_AWARE_PACKAGES)
            .should().dependOnClassesThat().areAssignableTo(EntityType.class);

    @ArchTest
    static final ArchRule 白名单外核心代码不得直接触碰静态上下文 = noClasses()
            .that().resideInAPackage("..core..")
            .and().resideOutsideOfPackages(ENTITY_AWARE_PACKAGES)
            .should().dependOnClassesThat().haveFullyQualifiedName(EntityContext.class.getName());

    // 文档 8.1.8：核心层禁止出现具体流程定义 key 之外的 BPMN 解析逻辑——
    // 解析（BpmnModel）只允许出现在 app 的装配冒烟测试中
    @ArchTest
    static final ArchRule 内核只依赖引擎API不做BPMN解析 = noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat().resideInAnyPackage("org.flowable.bpmn..");
}
