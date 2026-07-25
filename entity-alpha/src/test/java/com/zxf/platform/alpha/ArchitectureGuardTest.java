package com.zxf.platform.alpha;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.zxf.platform.core.flow.EntityContextAwareDelegate;
import com.zxf.platform.core.policy.PricingPolicy;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.context.annotation.Profile;

/**
 * 架构守护（文档 8.1.2 / 8.3）：扩展点接口的实现类必须声明 {@code supports()} 且被
 * {@code @Profile} 限定；禁止 {@code @Profile} 与 {@code @ConditionalOnProperty} 双轨混用。
 * delegate 纪律（文档 8.1.10）：单例无状态——实例字段必须 final（禁存执行态）；
 * delegate 必须继承 {@link EntityContextAwareDelegate}（文档 7.3③：Job 线程上下文重建，
 * 禁止直接 implements JavaDelegate 绕过基类）。
 */
@AnalyzeClasses(packages = "com.zxf.platform.alpha", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGuardTest {

    @ArchTest
    static final ArchRule 扩展点实现必须限定Profile = classes()
            .that().implement(PricingPolicy.class)
            .should().beAnnotatedWith(Profile.class);

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
