package com.zxf.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 引擎与 Flyway 启动顺序的装配断言（评审修复 M1）：
 * {@code processEngine} / {@code eventRegistryEngine}（Flowable 8 自动配置的引擎 bean，
 * 构建时逐一校验 ACT_* schema）必须依赖 {@code flywayInitializer}——顺序由
 * {@code FlowableDependsOnDatabaseInitializationDetector}（platform-flowable-starter
 * 的 spring.factories SPI）声明，本测试把"Detector 真的生效"钉成事实，防止 SPI 注册
 * 静默失效（如 spring.factories 路径写错、Boot 升级改注册机制）后回落到
 * "靠 JPA 间接排序"的巧合状态。
 */
@SpringBootTest
@ActiveProfiles("alpha")
@EnabledIfSystemProperty(named = "assembly.entity", matches = "alpha")
// 每测试类独立 H2 库（多上下文的 Flowable 引擎不能共享库，见 AlphaOrderApiEndToEndTest 同位置注释）
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:engine-flyway-order-db;DB_CLOSE_DELAY=-1")
class EngineDependsOnFlywayAssemblyTest {

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void 引擎bean必须依赖Flyway初始化器() {
        // getDependenciesForBean 含构造器依赖与显式 dependsOn——
        // DatabaseInitializationDependencyConfigurer 经后者注入 flywayInitializer
        var dependencies = context.getBeanFactory().getDependenciesForBean("processEngine");
        assertThat(dependencies)
                .as("processEngine 应 dependsOn flywayInitializer（Detector SPI 生效）；"
                        + "缺失时引擎先于迁移初始化，schema 校验将依赖 JPA 排序巧合")
                .contains("flywayInitializer");
    }

    @Test
    void 事件注册表引擎同样受顺序保护() {
        // eventRegistryEngine 独立校验 ACT_EVT_*（CLAUDE.md「四类 schema 逐一校验」），
        // 与主引擎同列 Detector 的保护范围
        var dependencies = context.getBeanFactory().getDependenciesForBean("eventRegistryEngine");
        assertThat(dependencies).contains("flywayInitializer");
    }

    @Test
    void 引擎服务bean经依赖链接到受保护的引擎() {
        // runtimeServiceBean（Flowable 命名）依赖 processEngine——顺序保护经依赖链传导
        var dependencies = context.getBeanFactory().getDependenciesForBean("runtimeServiceBean");
        assertThat(dependencies).contains("processEngine");
    }
}
