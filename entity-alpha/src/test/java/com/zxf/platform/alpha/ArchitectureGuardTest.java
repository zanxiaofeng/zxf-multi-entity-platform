package com.zxf.platform.alpha;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.zxf.platform.core.context.ForEntity;
import com.zxf.platform.core.domain.port.OrderStep;
import com.zxf.platform.core.domain.port.PricingPolicy;
import com.zxf.platform.core.infrastructure.engine.EntityContextAwareDelegate;
import org.flowable.engine.delegate.JavaDelegate;

/**
 * 架构守护（文档 8.1.2 / 8.3 / 5.10.1）：扩展点接口的实现类必须声明 {@code supports()} 且被
 * {@code @ForEntity} 复合注解限定（单一开关源 {@code platform.entity}）；禁止再散落裸
 * {@code @Profile} / {@code @ConditionalOnProperty} 硬编码形成双轨——断言精确到
 * {@code @ForEntity}（而非任意 {@code @Conditional} 元注解），把双轨路堵死。
 * delegate 纪律（文档 8.1.10）：单例无状态——实例字段必须 final（禁存执行态）；
 * delegate 必须继承 {@link EntityContextAwareDelegate}（文档 7.3③：Job 线程上下文重建，
 * 禁止直接 implements JavaDelegate 绕过基类）。
 *
 * <p>实体模块互禁依赖由 Maven Enforcer 在 jar 坐标粒度强制（entity-alpha pom
 * bannedDependencies 禁 entity-beta）；本测试源集 classpath 不含 beta 包，包级
 * ArchUnit 规则必然空转，故不在此声明（防"护栏存在"的错觉，文档 8.3 P0 修复）。
 */
@AnalyzeClasses(packages = "com.zxf.platform.alpha", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGuardTest {

    @ArchTest
    static final ArchRule 扩展点实现必须限定ForEntity = classes()
            .that().implement(PricingPolicy.class)
            .should().beAnnotatedWith(ForEntity.class); // 精确到 @ForEntity，堵死 @Profile/@ConditionalOnProperty 双轨

    @ArchTest
    static final ArchRule 管道步骤实现必须限定ForEntity = classes()
            .that().implement(OrderStep.class)
            .should().beAnnotatedWith(ForEntity.class);

    @ArchTest
    static final ArchRule delegate必须继承上下文重建基类 = classes()
            .that().implement(JavaDelegate.class)
            .and().areNotInterfaces()
            .should().beAssignableTo(EntityContextAwareDelegate.class);

    @ArchTest
    static final ArchRule delegate必须单例无状态 = fields()
            .that().areDeclaredInClassesThat().implement(JavaDelegate.class)
            .and().areNotStatic()
            .should().beFinal()
            .allowEmptyShould(true); // delegate 尚无实例字段时规则空转，允许
}
