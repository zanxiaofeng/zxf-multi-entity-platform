package com.zxf.platform.alpha;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.zxf.platform.core.context.EntityCapability;
import com.zxf.platform.core.context.ForEntity;
import com.zxf.platform.core.domain.port.OrderStep;
import com.zxf.platform.core.domain.port.PricingPolicy;
import com.zxf.platform.core.domain.port.TaskAssignmentRule;
import com.zxf.platform.core.infrastructure.engine.EntityContextAwareDelegate;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.context.annotation.Bean;

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
    static final ArchRule 候选人策略实现必须限定ForEntity = classes()
            .that().implement(TaskAssignmentRule.class)
            .should().beAnnotatedWith(ForEntity.class);

    /** 评审修复 M7-②：能力清单是 /actuator/info 第三道漂移防线——漏标 @ForEntity 则防线静默失能。 */
    @ArchTest
    static final ArchRule 能力清单实现必须限定ForEntity = classes()
            .that().implement(EntityCapability.class)
            .should().beAnnotatedWith(ForEntity.class);

    /**
     * 评审修复 M7-①：@Bean 工厂方法返回扩展点类型可绕过 @ForEntity 限定（@ForEntity 的
     * @Target 仅 TYPE，方法上无法标注；未经条件装配的实例会被 PolicyRegistry 照常收集）。
     * 堵死绕过面：扩展点实现一律 @Component + @ForEntity 声明。当前无实例故允许空转
     * （allowEmptyShould——出现首个 @Bean 返回扩展点时规则先红，强制走声明式路径）。
     */
    @ArchTest
    static final ArchRule Bean工厂方法禁止返回计价策略 = noMethods()
            .that().areAnnotatedWith(Bean.class)
            .should().haveRawReturnType(PricingPolicy.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule Bean工厂方法禁止返回管道步骤 = noMethods()
            .that().areAnnotatedWith(Bean.class)
            .should().haveRawReturnType(OrderStep.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule Bean工厂方法禁止返回候选人策略 = noMethods()
            .that().areAnnotatedWith(Bean.class)
            .should().haveRawReturnType(TaskAssignmentRule.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule Bean工厂方法禁止返回能力清单 = noMethods()
            .that().areAnnotatedWith(Bean.class)
            .should().haveRawReturnType(EntityCapability.class)
            .allowEmptyShould(true);

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
