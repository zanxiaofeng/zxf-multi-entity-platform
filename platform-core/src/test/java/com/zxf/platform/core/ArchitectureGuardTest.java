package com.zxf.platform.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;

/**
 * 架构守护（文档 5.1.1 / 8.3）：
 * <ol>
 *   <li>六边形（洋葱）分层替代旧的单点白名单规则——domain 核心不依赖外层，
 *       适配器只经端口进入内核；</li>
 *   <li>实体感知（{@link EntityType} / {@link EntityContext}）收敛到白名单包；</li>
 *   <li>领域对象禁 setter（文档 8.1 规则 12，5.9 军规 9）；</li>
 *   <li>内核只依赖引擎 API、不做 BPMN 解析（文档 8.1 规则 8）。</li>
 * </ol>
 */
@AnalyzeClasses(packages = "com.zxf.platform.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGuardTest {

    /**
     * 六边形分层（文档 5.1.1）。与文档示例的两点差异：
     * filter/observation 两个既有适配器一并声明（同为入站/出站边缘）；
     * consumer/messaging 尚不存在——{@code withOptionalLayers} 允许空层，
     * 未来新增类落入即被约束。context 包是横切"隐式参数"，不作为层参与规则（文档原注）。
     */
    @ArchTest
    static final ArchRule 六边形分层 = Architectures.onionArchitecture()
            .domainModels("..domain.model..", "..domain.event..")
            .domainServices("..domain.service..", "..domain.port..")
            .applicationServices("..application..")
            .adapter("rest", "..interfaces.rest..")
            .adapter("consumer", "..interfaces.consumer..")
            .adapter("filter", "..interfaces.filter..")
            .adapter("persistence", "..infrastructure.persistence..")
            .adapter("engine", "..infrastructure.engine..")
            .adapter("messaging", "..infrastructure.messaging..")
            .adapter("observation", "..infrastructure.observation..")
            .withOptionalLayers(true);

    /**
     * 允许感知实体的白名单包：context（上下文自身）、application.port（端口解析机制，
     * 文档 5.1.1 豁免）、interfaces.filter（上下文入口）、infrastructure.engine（引擎适配，
     * 文档 7.3 与 Filter 同级豁免）、infrastructure.observation（审计打实体维度）、
     * domain.port（{@code supports()} 以 EntityType 声明适配实体，属端口契约，文档 5.2.4）。
     * 其余 core 包（application 业务编排、domain 核心、interfaces.rest、
     * infrastructure.persistence 及未来新增）一律禁止。
     */
    private static final String[] ENTITY_AWARE_PACKAGES = {
            "..core.context..", "..core.application.port..", "..core.interfaces.filter..",
            "..core.infrastructure.engine..", "..core.infrastructure.observation..",
            "..core.domain.port.."};

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

    /**
     * 领域核心零实体感知（文档 5.1.1）。文档原文范围为 {@code ..domain..}，与文档自身布局
     * 矛盾（{@code domain.port.PricingPolicy.supports()} 返回 EntityType）——按布局语义
     * 收窄为 model/service/event：领域核心不知道"有几个实体"，端口契约除外。
     */
    @ArchTest
    static final ArchRule 领域核心零实体感知 = noClasses()
            .that().resideInAPackage("..domain.model..")
            .or().resideInAPackage("..domain.service..")
            .or().resideInAPackage("..domain.event..")
            .should().dependOnClassesThat().areAssignableTo(EntityType.class);

    /** 应用层仅端口解析器可感知实体（文档 5.1.1 原文）。 */
    @ArchTest
    static final ArchRule 应用层仅端口解析器可感知实体 = noClasses()
            .that().resideInAPackage("..application..")
            .and().resideOutsideOfPackage("..application.port..")
            .should().dependOnClassesThat().areAssignableTo(EntityType.class);

    /** 领域对象禁 setter（文档 8.1 规则 12，5.9 军规 9）：状态变更走领域方法。 */
    @ArchTest
    static final ArchRule 领域对象禁setter = noMethods()
            .that().areDeclaredInClassesThat().resideInAPackage("..domain..")
            .should().haveNameStartingWith("set");

    // 文档 8.1.8：核心层禁止出现具体流程定义 key 之外的 BPMN 解析逻辑——
    // 解析（BpmnModel）只允许出现在 app 的装配冒烟测试中
    @ArchTest
    static final ArchRule 内核只依赖引擎API不做BPMN解析 = noClasses()
            .that().resideInAPackage("..core..")
            .should().dependOnClassesThat().resideInAnyPackage("org.flowable.bpmn..");
}
