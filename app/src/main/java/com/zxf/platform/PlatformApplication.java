package com.zxf.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 单一启动类，无实体感知（文档 5.4.2）。
 *
 * <p>放在根包 {@code com.zxf.platform}：默认组件扫描覆盖 core 与被装配的实体模块，
 * JPA 实体/仓库的自动配置包（AutoConfigurationPackages）同样落在根包下，无需额外声明。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
