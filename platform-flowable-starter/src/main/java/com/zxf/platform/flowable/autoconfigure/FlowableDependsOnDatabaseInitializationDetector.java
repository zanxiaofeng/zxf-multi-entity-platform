package com.zxf.platform.flowable.autoconfigure;

import java.util.Set;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitializationDetector;

/**
 * Flowable 引擎对数据库初始化的启动顺序保障（评审修复 M1）。
 *
 * <p><b>问题</b>：本工程 {@code flowable.database-schema-update=false}——ACT_* 表全靠
 * Flyway（{@code common/V3__flowable_engine_tables.sql}）建表；而 Flowable 8.0.0 未注册
 * {@link DependsOnDatabaseInitializationDetector} SPI，Boot 无法感知"引擎 bean 依赖数据库
 * 初始化"。当前能正常启动仅因 JPA 的 EntityManagerFactory 间接拖住了 bean 排序——
 * 一旦移除 JPA 或 bean 图变化，引擎会先于 Flyway 初始化，schema 校验确定性失败。
 *
 * <p><b>机制</b>：Boot 的 {@code DatabaseInitializationDependencyConfigurer} 消费本 SPI，
 * 为 {@link #detect} 返回的 bean 名追加对数据库初始化器（{@code flywayInitializer}）的
 * {@code dependsOn}，把隐式巧合变成显式顺序契约。注册经 {@code META-INF/spring.factories}
 * （该 SPI 未随 Boot 4 模块化迁移到 AutoConfiguration.imports，仍走 spring.factories）。
 *
 * <p><b>覆盖的引擎 bean</b>（{@code flowable-spring-boot-starter-process} 路径，
 * {@code AppEngine} 全家桶不在本工程 classpath）：
 * <ul>
 *   <li>{@code processEngine}——主引擎，构建时校验 common / process / history / idm 四组 schema；</li>
 *   <li>{@code eventRegistryEngine}——事件注册表引擎，独立校验 ACT_EVT_*。</li>
 * </ul>
 * 装配正确性由 app 模块 {@code EngineDependsOnFlywayAssemblyTest} 断言。
 */
public class FlowableDependsOnDatabaseInitializationDetector
        implements DependsOnDatabaseInitializationDetector {

    /** 引擎 bean 名（Flowable spring-boot 自动配置的既有命名，非本工程自定义）。 */
    private static final Set<String> ENGINE_BEAN_NAMES = Set.of("processEngine", "eventRegistryEngine");

    /** 声明存在的引擎 bean；无 Flowable 的工程返回空集（悬空 dependsOn 名会让启动失败，故先探测）。 */
    @Override
    public Set<String> detect(ConfigurableListableBeanFactory beanFactory) {
        return ENGINE_BEAN_NAMES.stream()
                .filter(beanFactory::containsBean)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
