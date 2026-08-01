package com.zxf.platform.alpha;

import static org.assertj.core.api.Assertions.assertThat;

import com.zxf.platform.core.application.port.PolicyRegistry;
import com.zxf.platform.core.context.EntityContext;
import com.zxf.platform.core.context.EntityType;
import com.zxf.platform.core.context.PlatformProperties;
import com.zxf.platform.core.domain.model.Money;
import com.zxf.platform.core.domain.model.Order;
import com.zxf.platform.core.domain.port.OrderStep;
import com.zxf.platform.core.domain.port.PricingPolicy;
import com.zxf.platform.core.domain.service.AbstractDocumentGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Alpha 装配冒烟（文档 5.7）：防交叉污染——只装配 Alpha 实现且注册表可按当前实体计价；
 * 管道步骤与单据生成器按实体各取所需（文档 5.8）。
 *
 * <p>轻量 Spring 上下文（不依赖 Boot 自动配置），随 entity-alpha 模块构建永远运行；
 * 完整启动级冒烟（含公共 Schema 步骤在内的全管道序列）见 app 模块的 {@code @SpringBootTest} 矩阵。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AlphaAssemblySmokeTest.TestAssembly.class)
@ActiveProfiles("alpha")
@TestPropertySource(properties = "platform.entity=alpha")
class AlphaAssemblySmokeTest {

    @Configuration
    @EnableConfigurationProperties(PlatformProperties.class)
    @Import(PolicyRegistry.class)
    @ComponentScan("com.zxf.platform.alpha")
    static class TestAssembly {
        // delegate 基类（组件 3）依赖 MeterRegistry；轻量上下文不含 Boot 自动配置，手工注册一个
        // SimpleMeterRegistry 以满足装配（生产环境由 actuator 自动配置提供）
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private List<PricingPolicy> policies;

    @Autowired
    private List<OrderStep> orderSteps;

    @Autowired
    private List<AbstractDocumentGenerator> documentGenerators;

    @Autowired
    private PolicyRegistry registry;

    @AfterEach
    void tearDown() {
        EntityContext.clear();
    }

    @Test
    void 只装配Alpha实现且注册表可按当前实体计价() {
        assertThat(policies)
                .extracting(PricingPolicy::supports)
                .containsExactly(EntityType.ALPHA);

        EntityContext.set(EntityType.ALPHA);
        assertThat(registry.priceFor(Order.from("widget", 1)))
                .isEqualTo(Money.cny("113.00")); // 100 * 1.13
    }

    @Test
    void 装配Alpha专属管道步骤与单据生成器() {
        // 本模块扫描仅含实体适配器：公共 Schema 步骤归 core，由 app 级冒烟断言完整序列
        assertThat(orderSteps)
                .extracting(OrderStep::name)
                .containsExactly("risk-check");
        assertThat(documentGenerators).hasSize(1);
    }
}
