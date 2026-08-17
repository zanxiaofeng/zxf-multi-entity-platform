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
- MySQL 8.0 (production)
- H2 (testing)

## Testing

- JUnit 5
- AssertJ
- Spring Cloud Contract（与 Spring Boot 4 兼容版本，属 Spring Cloud 2025.1.x release train；具体版本号见 [Supported Versions](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions)）
- WireMock 3.x

## Infrastructure

- Lombok（boilerplate reduction：`@Data`、`@Builder`、`@Slf4j`、`@RequiredArgsConstructor`）
- Apache Commons Lang 3（`StringUtils`、`ObjectUtils`）
- Flyway（SB4 需专用 starter `spring-boot-starter-flyway`；版本由 Spring Boot BOM 管理）
- Spring Data JPA（Hibernate 7）
- Jakarta Validation 3.1（`spring-boot-starter-validation`）
- Jackson 3（SB4 默认 JSON 库，核心包为 `tools.jackson`；`jackson-annotations` 仍为 `com.fasterxml.jackson.annotation`）
- Spring Web MVC（`spring-boot-starter-webmvc`）
- Spring Security（CSRF configuration per project requirements）
- RestClient / RestTemplate（下游 HTTP 客户端，需 `spring-boot-starter-restclient`；RestClient 为新模块首选，RestTemplate 处理维护模式）

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
