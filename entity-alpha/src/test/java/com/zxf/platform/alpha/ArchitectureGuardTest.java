package com.zxf.platform.alpha;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.zxf.platform.core.policy.PricingPolicy;
import org.springframework.context.annotation.Profile;

/**
 * 架构守护（文档 7.1.2 / 7.3）：扩展点接口的实现类必须声明 {@code supports()} 且被
 * {@code @Profile} 限定；禁止 {@code @Profile} 与 {@code @ConditionalOnProperty} 双轨混用。
 */
@AnalyzeClasses(packages = "com.zxf.platform.alpha", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGuardTest {

    @ArchTest
    static final ArchRule 扩展点实现必须限定Profile = classes()
            .that().implement(PricingPolicy.class)
            .should().beAnnotatedWith(Profile.class);
}
