---
paths:
  - "**/application/port/out/**/*.java"
  - "**/infrastructure/**/*.java"
  - "**/integration/**/*.java"
  - "**/e2e/**/*.java"
  - "**/*.yml"
  - "**/*.yaml"
  - "**/*.properties"
---
# Downstream Integration Conventions

> **职责边界：** 本文件是下游集成的**唯一权威**——设计原则、HTTP 客户端实现（RestClient/RestTemplate）、错误分类、弹性模式、连接池、接口设计、日志、测试配置。`architecture.md` §5.2 仅概述 Port/Impl 位置，`service-conventions.md` §3 定义事务内禁止外部调用与事件委托。

***

## 1. Design Principle

- 下游服务接口（Gateway）在 **application 层**（`application/port/out/{Service}Gateway.java`），实现在 **infrastructure 出站适配器**（`infrastructure/adapter/out/external/{Service}GatewayAdapter.java`）
- 使用 `RestClient`（首选，Spring Framework 7，需 `spring-boot-starter-restclient`）或 `RestTemplate` 做 HTTP 调用
- 禁止 Controller 或 Service 直接调用下游，必须通过 Gateway 端口；外部 HTTP 客户端、序列化细节止步于出站适配器
- 方法参数使用 Command/事件 record，**禁止超过 3 个原始参数**
- 下游调用不得出现在 `@Transactional` 方法内——经 `EventPublisher` 发布事件，出站适配器 `afterCommit` 后调用（见 `service-conventions.md` §3）

***

## 2. RestClient 实现（首选）

```java
// Config — infrastructure/adapter/out/external/config/{Service}Config.java（就近管理）
@Configuration
public class {Service}Config {
    @Bean
    public RestClient {service}RestClient(RestClient.Builder builder,
            @Value("${app.downstream.{service}.base-url}") String baseUrl) {
        return builder
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}

// Implementation — infrastructure/adapter/out/external/{Service}GatewayAdapter.java
// implements application/port/out/{Service}Gateway
@Slf4j
@Component
@RequiredArgsConstructor
public class {Service}GatewayAdapter implements {Service}Gateway {
    private final RestClient {service}RestClient;

    @Override
    public boolean sendNotification({Event}Event event) {
        try {
            {service}RestClient.post()
                .uri("/api/v1/notifications")
                .body(event)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    log.error("Downstream {service} error: {} {}", res.getStatusCode(), req.getURI());
                })
                .toBodilessEntity();
            return true;
        } catch (ResourceAccessException ex) {
            log.warn("Downstream {service} unreachable: {}", ex.getMessage());
            return false;
        } catch (Exception ex) {
            log.error("Failed to call {service}, key: {}", event.key(), ex);
            return false;
        }
    }
}
```

> 出站适配器调用外部系统失败时，按业务决策：降级返回（`boolean` / 默认值）或翻译为领域异常（带 cause，`exception-handling.md` §2）——不把 HTTP 客户端异常泄露到应用层。

***

## 3. RestTemplate 实现（已有项目）

```java
@Bean
public RestTemplate downstreamRestTemplate(RestTemplateBuilder builder) {
    return builder
            .setConnectTimeout(Duration.ofSeconds(3))
            .setReadTimeout(Duration.ofSeconds(5))
            .build();
}
```

> 已有项目使用 `RestTemplate` 可继续使用。新模块推荐 `RestClient`。

***

## 4. 错误分类处理

下游 HTTP 错误分为三类，处理策略不同：

| 错误类型 | 异常类 | 处理策略 |
|----------|--------|----------|
| 连接失败/超时 | `ResourceAccessException` | 记录 WARN + 返回 false（瞬态错误，可重试） |
| 客户端错误 (4xx) | `HttpClientErrorException` | 记录 ERROR + 业务决策（参数错误？认证过期？） |
| 服务端错误 (5xx) | `HttpServerErrorException` | 记录 ERROR + 降级处理（可重试/熔断） |

**RestClient `onStatus` 精细化处理：**

```java
.retrieve()
.onStatus(status -> status.is4xxClientError(), (req, res) -> {
    // 4xx: 业务错误，记录并决定是否传播
    log.warn("Downstream 4xx: {} {}", res.getStatusCode(), req.getURI());
})
.onStatus(status -> status.is5xxServerError(), (req, res) -> {
    // 5xx: 服务端问题，可重试
    log.error("Downstream 5xx: {} {}", res.getStatusCode(), req.getURI());
})
```

> 下游异常的完整捕获/处理规范见 `exception-handling.md` §5。

***

## 5. 弹性模式（生产推荐）

使用 Resilience4j 增强下游调用可靠性：

```java
// pom.xml（SB4 需使用支持 Spring Boot 4 的 resilience4j 版本，artifact 为 resilience4j-spring-boot）
// <dependency>
//     <groupId>io.github.resilience4j</groupId>
//     <artifactId>resilience4j-spring-boot</artifactId>
// </dependency>

@Slf4j
@Component
@RequiredArgsConstructor
public class {Service}GatewayAdapter implements {Service}Gateway {
    private final RestClient {service}RestClient;

    @Override
    @CircuitBreaker(name = "{service}", fallbackMethod = "sendNotificationFallback")
    @Retry(name = "{service}")
    public boolean sendNotification({Event}Event event) {
        // ... RestClient 调用
    }

    private boolean sendNotificationFallback({Event}Event event, Exception ex) {
        log.warn("Circuit breaker/fallback for {service}: {}", ex.getMessage());
        return false;
    }
}
```

```yaml
# application.yml
resilience4j:
  circuitbreaker:
    instances:
      {service}:
        failure-rate-threshold: 50
        slow-call-duration-threshold: 3s
        slow-call-rate-threshold: 80
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
  retry:
    instances:
      {service}:
        max-attempts: 3
        wait-duration: 500ms
        retry-exceptions:
          - org.springframework.web.client.ResourceAccessException
```

***

## 6. 连接池配置（生产环境）

默认 `RestTemplate` / `RestClient` 每个请求打开新 TCP 连接。生产环境推荐连接池：

```java
@Bean
public RestTemplate downstreamRestTemplate(RestTemplateBuilder builder) {
    var httpClient = HttpClients.custom()
        .setMaxConnTotal(50)
        .setMaxConnPerRoute(10)
        .build();

    var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setConnectionRequestTimeout(Duration.ofSeconds(2));

    return builder.requestFactory(() -> factory).build();
}
```

***

## 7. Gateway 接口设计

```java
// application/port/out/{Service}Gateway.java

// GOOD: 使用 Command/Event DTO
public interface {Service}Gateway {
    boolean sendNotification({Event}CreatedEvent event);
}

// BAD: 超过 3 个原始参数
public interface {Service}Gateway {
    boolean sendNotification(Long userId, String username, String email); // 违反规则
}
```

***

## 8. 下游调用日志

- **DEBUG 级别**：请求 URL、HTTP 方法、响应状态码
- **ERROR 级别**：调用失败，包含下游服务名称和关键参数（脱敏后）
- **禁止**：记录完整请求体/响应体（可能包含敏感数据）

> 日志规范详见 `logging.md`。

***

## 9. 测试配置

Production: `app.downstream.{service}.base-url` in `application.yml`
Test: `app.downstream.{service}.base-url` pointing to `http://localhost:${wiremock.server.port}` in `application-test.yml`

### WireMock 测试模式

```java
// MockFileLoader — support/mocks/MockFileLoader.java
// 加载 mock-data/ 目录下的 JSON 文件，支持 ${variable} 模板变量替换
@UtilityClass
public class MockFileLoader {  // @UtilityClass 自动 final + 方法自动 static(勿手写 final/static)
    public String load(String resourcePath) { ... }
    public String load(String resourcePath, Map<String, String> variables) { ... }
}

// MockFactory — support/mocks/{Service}MockFactory.java
// 使用 MockFileLoader 加载 request/response 模板，通过 equalToJson 匹配请求体
@UtilityClass
public class {Service}MockFactory {

    /** 请求体模板 — 使用 ${json-unit.ignore} 通配匹配任意字段值 */
    private static final String REQUEST_BODY =
            MockFileLoader.load("request/{service}-{action}.json");

    public static void mock{Service}Success() {
        String responseBody = MockFileLoader.load("response/{service}-success.json");

        WireMock.stubFor(WireMock.post(urlEqualTo("/api/v1/{path}"))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(equalToJson(REQUEST_BODY))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(responseBody)));
    }

    public static void mock{Service}Failure() {
        String responseBody = MockFileLoader.load("response/{service}-failure.json");

        WireMock.stubFor(WireMock.post(urlEqualTo("/api/v1/{path}"))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(equalToJson(REQUEST_BODY))
                .willReturn(WireMock.aResponse()
                        .withStatus(500)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(responseBody)));
    }
}

// MockVerifier — support/mocks/{Service}MockVerifier.java
@UtilityClass
public class {Service}MockVerifier {
    public static void verify{Service}Called(int count) {
        WireMock.verify(count, postRequestedFor(urlEqualTo("/api/v1/{path}"))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo(MediaType.APPLICATION_JSON_VALUE)));
    }

    public static void verify{Service}CalledWith(String key, String value) {
        WireMock.verify(postRequestedFor(urlEqualTo("/api/v1/{path}"))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(containing("\"" + key + "\":\"" + value + "\"")));
    }
}
```

**mock-data 模板文件**：

| 文件 | 用途 | 占位符 |
|------|------|--------|
| `mock-data/request/{service}-{action}.json` | 请求体匹配模板 | `${json-unit.ignore}`（通配）、`${variable}`（特定值） |
| `mock-data/response/{service}-{scenario}.json` | 响应体返回模板 | `${variable}`（运行时替换动态值） |

**请求模板示例** — 所有字段使用 `${json-unit.ignore}` 通配匹配：

```json
{
  "userId": "${json-unit.ignore}",
  "username": "${json-unit.ignore}",
  "email": "${json-unit.ignore}",
  "eventType": "${json-unit.ignore}"
}
```

> WireMock 的 `equalToJson()` 底层使用 json-unit，支持 `${json-unit.ignore}` 占位符进行结构匹配。

> WireMock 测试模式与 MockFactory/MockVerifier 完整规范见 `integration-test-guide.md` §6。
