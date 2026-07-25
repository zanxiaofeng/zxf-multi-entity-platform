package com.zxf.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 启动级负例（文档 5.7 / 6.3）：{@code platform.entity} 指向"另一个实体"时，
 * 应用必须启动失败——注册表构造器校验是配置漂移的第一道防线。
 *
 * <p>两种装配下覆盖两类漂移：
 * <ul>
 *   <li>{@code -Palpha}：激活 alpha profile 但 entity=beta → 实现与实体不匹配；</li>
 *   <li>{@code -Pbeta}：激活 beta profile 但 entity=alpha → 同上对称。</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "assembly.entity", matches = "alpha|beta")
class MisconfiguredAssemblyTest {

    @Test
    void profile与entity漂移时启动失败() {
        String current = System.getProperty("assembly.entity");
        String other = "alpha".equals(current) ? "beta" : "alpha";

        var app = new SpringApplicationBuilder(PlatformApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(current);

        // 用命令行参数注入（优先级高于 application-*.yaml）：
        // platform.entity 指向另一个实体制造漂移；独立 H2 库 + 免迁移/引擎自建表，
        // 把注册表防线隔离为唯一失败点，也避免污染同 JVM 内其他测试共享的库
        assertThatThrownBy(() -> app.run(
                        "--platform.entity=" + other,
                        "--spring.datasource.url=jdbc:h2:mem:misconfigured-db;DB_CLOSE_DELAY=-1",
                        "--spring.flyway.enabled=false",
                        "--spring.jpa.hibernate.ddl-auto=create-drop",
                        "--flowable.database-schema-update=true"))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未装配 PricingPolicy");
    }
}
