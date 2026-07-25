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
 * 只允许出现在注册表、上下文、Filter 中——业务服务里出现即打回。
 */
@AnalyzeClasses(packages = "com.zxf.platform.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGuardTest {

    @ArchTest
    static final ArchRule 核心服务不得感知实体枚举 = noClasses()
            .that().resideInAPackage("..core.service..")
            .should().dependOnClassesThat().areAssignableTo(EntityType.class);

    @ArchTest
    static final ArchRule 核心服务不得直接触碰静态上下文 = noClasses()
            .that().resideInAPackage("..core.service..")
            .should().dependOnClassesThat().haveFullyQualifiedName(EntityContext.class.getName());

    // 文档 8.1.8：核心层禁止出现具体流程定义 key 之外的 BPMN 解析逻辑——
    // 解析（BpmnModel）只允许出现在 app 的装配冒烟测试中
    @ArchTest
    static final ArchRule 内核只依赖引擎API不做BPMN解析 = noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat().resideInAnyPackage("org.flowable.bpmn..");
}
