---
paths:
  - "**/pom.xml"
  - "**/*.java"
  - "**/*.yml"
  - "**/*.yaml"
  - "**/*.properties"
---
# Tech Stack

- Java 21（Spring Boot 4 要求 Java 17+，推荐使用 LTS 版本）
- Spring Boot 4.1.x
- Spring Framework 7.x
- Jakarta EE 11（Servlet 6.1 baseline）
- Maven 3.9+
- MySQL 8.0 (production & integration/e2e testing via Testcontainers)

## Testing

- JUnit 5
- AssertJ
- Testcontainers 2.x（integration/e2e 用真实 MySQL 容器；H2 仅作无法使用容器时的降级选项）
- ArchUnit 1.5.x（架构守护测试 `unit/HexagonalArchitectureTest`，依赖方向铁律自动化强制；BOM 不管理，显式声明版本）
- Spring Cloud Contract（与 Spring Boot 4 兼容版本，属 Spring Cloud 2025.1.x release train；具体版本号见 [Supported Versions](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions)）
- WireMock 3.13.2（`org.wiremock:wiremock-standalone`；Java 包名仍为 `com.github.tomakehurst.wiremock`；4.x 处于 beta，暂不跟进）

**SB4 测试依赖与注解包名（实测 4.1.0，与 SB3 差异）：**

| 事项 | SB4 现状 |
|------|---------|
| Testcontainers | **2.x 坐标更名**：`testcontainers-junit-jupiter` / `testcontainers-mysql`；版本由 Boot BOM 导入的 testcontainers-bom 管理（4.1.0 → 2.0.5），**无需显式声明**；容器类迁移至 `org.testcontainers.mysql.MySQLContainer`（旧包 `org.testcontainers.containers` 废弃），泛型参数已移除 |
| `@DataJpaTest` | 拆分至 `spring-boot-starter-data-jpa-test`；包名 `org.springframework.boot.data.jpa.test.autoconfigure` |
| `@AutoConfigureMockMvc` | 包名 `org.springframework.boot.webmvc.test.autoconfigure`（随 starter-webmvc-test 提供） |
| `@AutoConfigureTestDatabase` | 包名 `org.springframework.boot.jdbc.test.autoconfigure`（随 data-jpa-test 传递） |
| `@ServiceConnection` | 需依赖 `spring-boot-testcontainers`；包名不变 |
| ArchUnit | `com.tngtech.archunit:archunit-junit5`（架构守护测试，`unit/HexagonalArchitectureTest`）；BOM 不管理，需显式声明版本 |

## Infrastructure（SB4 实测补充）

- **Flyway 10+ 数据库方言拆分**：MySQL 支持需额外依赖 `org.flywaydb:flyway-mysql`（版本 BOM 管理），否则启动报 `Unsupported Database: MySQL 8.0`
- **RestClient 请求工厂**：默认 JDK `HttpURLConnection` 的 keep-alive 连接池会复用被服务端关闭的空闲连接（报 `EOF reached while reading`）；引入 `org.apache.httpcomponents.client5:httpclient5` 后 Spring Boot 自动改用其连接池（带 stale 校验）

## Infrastructure

- Lombok（boilerplate reduction：`@Data`、`@Builder`、`@Slf4j`、`@RequiredArgsConstructor`；**仅 application 与 infrastructure 层使用，domain 层零依赖**）
- Apache Commons Lang 3（`StringUtils`、`ObjectUtils`）
- Flyway（SB4 需专用 starter `spring-boot-starter-flyway`；版本由 Spring Boot BOM 管理）
- Spring Data JPA（Hibernate 7）
- Jakarta Validation 3.1（`spring-boot-starter-validation`）
- Jackson 3（SB4 默认 JSON 库，核心包为 `tools.jackson`；`jackson-annotations` 仍为 `com.fasterxml.jackson.annotation`）
- Spring Web MVC（`spring-boot-starter-webmvc`）
- Spring Security（CSRF configuration per project requirements）
- RestClient / RestTemplate（下游 HTTP 客户端，需 `spring-boot-starter-restclient`；RestClient 为新模块首选，RestTemplate 处理维护模式）
- Kafka（按需；SB4 模块化 starter `spring-boot-starter-kafka`，自动配置 `KafkaTemplate` 等基础设施）

## Starter 模块化（Spring Boot 4 关键变化）

Spring Boot 4 采用模块化设计：每个技术有专用 starter，且每个 starter 配套一个 `-test` starter。

| 旧 starter（SB 3.5） | Spring Boot 4 starter | 说明 |
|----------------------|------------------------|------|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` | Servlet Web MVC（旧名已 deprecated 但保留） |
| `spring-boot-starter-web-services` | `spring-boot-starter-webservices` | Spring WS |
| `spring-boot-starter-aop` | `spring-boot-starter-aspectj` | AspectJ / AOP |
| `spring-boot-starter-oauth2-*` | `spring-boot-starter-security-oauth2-*` | OAuth2 starter 加 `security-` 前缀 |
| （仅第三方依赖） | `spring-boot-starter-flyway` | Flyway 现需专用 starter |
| （仅第三方依赖） | `spring-boot-starter-restclient` | RestClient/RestTemplate 现需专用 starter |
| `spring-boot-starter-validation` | `spring-boot-starter-validation` | 名称不变，确认显式引入 |

**Test starter 配套规则：** `spring-boot-starter-<tech>-test`（如 `spring-boot-starter-webmvc-test`、`spring-boot-starter-restclient-test`）已传递引入 `spring-boot-starter-test`，无需再单独声明后者。

> 过渡期可用 `spring-boot-starter-classic` / `spring-boot-starter-test-classic` 快速恢复「全部自动配置可用」的类路径以修复 import，但官方建议最终迁移到模块化 starter。

## Downstream Integration

- WireMock 3.x（test stubbing for downstream services）
- RestClient with 3s connect / 5s read timeout（详见 `downstream-conventions.md`）

## 多模块说明（进阶）

当前为单模块结构；多模块拆分（domain / application / infrastructure / bootstrap）见 `docs/SpringBoot六边形架构包结构设计指南.md` 第十章。注意：自建 parent + import BOM 时，**BOM 只管理依赖版本不管理插件版本**，`spring-boot-maven-plugin` 必须在父 POM `<pluginManagement>` 中显式声明版本。
