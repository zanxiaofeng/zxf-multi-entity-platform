package com.zxf.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * 跨实体护栏（文档 8.3 P0 已落地）：app 是组合根，测试源集做"装配正确性"自检守护。
 *
 * <p><b>核心价值是自检测试</b>：断言当前装配实体的包确实被 ArchUnit 导入——若未来
 * 包路径漂移或 ArchUnit 配置错误导致扫描范围失效，自检会先失败（而非让规则静默空转）。
 * 这正是文档 8.3 P0 修复的目标：防"护栏从未失败过"的静默失效。
 *
 * <p><b>跨实体互禁依赖不在本类检查</b>：app 单一 profile 下只依赖一个实体模块
 * （-Palpha 时 entity-beta 不在 classpath），ArchUnit 看不到另一实体包，规则必然空转。
 * 跨实体互禁依赖由 <b>Maven Enforcer</b> 在 jar 坐标粒度强制（entity-alpha/pom.xml
 * bannedDependencies 禁 entity-beta，对称），强度高于 ArchUnit 包级规则。
 *
 * <p><b>扩展点激活注解守护</b>不在本类检查：扩展点分两类——公共步骤（core 的
 * {@code SchemaValidationStep}，不带 @ForEntity，两实体共用）+ 实体专属（alpha/beta
 * 的 {@code AlphaPricingPolicy} 等，带 @ForEntity）。app 级扫描全包无法区分两类，
 * 规则要么误伤公共步骤、要么放宽失去意义。实体专属扩展点的 @ForEntity 守护由
 * entity-alpha / entity-beta 自己的 ArchitectureGuardTest 承担（扫描各自实体包）。
 */
@AnalyzeClasses(packages = "com.zxf.platform", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGuardTest {

    /**
     * 自检：防静默失效——断言当前装配实体的包确实被导入。
     *
     * <p>app surefire 已注入 {@code assembly.entity} 系统属性（app/pom.xml），
     * 与 {@code -Palpha/-Pbeta} 当前装配实体对齐。
     */
    @ArchTest
    static void 当前装配实体包必须被导入(JavaClasses classes) {
        var entity = System.getProperty("assembly.entity");
        assertThat(entity)
                .as("assembly.entity 系统属性必须注入（app surefire 配置）")
                .isNotBlank();
        assertThat(classes)
                .as("当前装配实体 (%s) 的包必须被导入，否则实体级 ArchUnit 规则全空转", entity)
                .anyMatch(c -> c.getPackageName().startsWith("com.zxf.platform." + entity));
    }
}
