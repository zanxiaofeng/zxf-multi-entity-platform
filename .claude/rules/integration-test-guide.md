---
paths:
  - "**/e2e/**/*.java"
  - "**/test/resources/**"
---

# E2E Test 编码规范

> **职责边界：** 本文件是 E2E Test 的**详细编码指南**——命名、Fixture、模板、Support 类、三层断言、WireMock 模式、SFTP Mock、配置。测试分层、包结构、Context 管理、独立性规则见 `test-conventions.md`。

***

## 1. 总览与设计原则

E2E Test 启动完整的 Spring Boot 应用，通过 `MockMvc` 走完整请求链路（DispatcherServlet → Controller → Service → Repository → Database + Downstream），验证端到端行为。

| 原则 | 说明 |
|------|------|
| **完整上下文** | `@SpringBootTest` + `@AutoConfigureMockMvc`，全量 Bean 装配，走真实 DispatcherServlet 链路 |
| **真实 MySQL** | Testcontainers 起真实 MySQL 容器，Flyway 建表——与生产同方言，无 H2 模拟偏差 |
| **@Sql 管理数据** | 预置种子数据，不通过 API 运行时创建 |
| **JSON Fixture** | 请求/响应使用 JSON 文件 + 模板变量，不硬编码 |
| **json-unit** | 使用 json-unit 断言，支持 `${json-unit.ignore}` 占位符、忽略额外字段和数组顺序 |
| **DB 直验** | 通过 DatabaseVerifier 直接查询 DB 验证状态 |
| **MockFactory/Verifier** | WireMock 下游 mock 通过 Factory 创建、Verifier 验证 |
| **Given/When/Then** | 每个测试方法严格遵循三段式结构 |

> **Spring Boot 4 提示（重要）：**
> - `@SpringBootTest` **不再自动注入 MockMvc**，基类必须显式加 `@AutoConfigureMockMvc`
> - Mock 注解统一用 `@MockitoBean` / `@MockitoSpyBean`（`@MockBean` 已移除）
> - 不使用 WebTestClient：SB4 模块化下其自动配置不可靠，且需额外引入 webflux 依赖；MockMvc 覆盖同样的断言能力

> **测试包结构：** 完整目录结构见 `test-conventions.md` Test Package Structure。

***

## 2. 命名规范

### 类命名

| 类型 | 格式 | 示例 |
|------|------|------|
| 测试类 | `{Entity}FlowTest` | `UserFlowTest`, `OrderFlowTest` |
| Mock Factory | `{Service}MockFactory` | `PaymentMockFactory` |
| Mock Verifier | `{Service}MockVerifier` | `PaymentMockVerifier` |

### 方法命名

格式：`test{Action}{Entity}[{Condition}]`，如 `testCreateUser`、`testCreateUserWithValidationError`、`testGetUserByIdNotFound`。

### 文件命名

| 类型 | 路径格式 |
|------|---------|
| 请求 fixture | `test-data/{entity}/{operation}/request.json` |
| 成功响应 | `test-data/{entity}/{operation}/ok.json` 或 `created.json` |
| 错误响应 | `test-data/{entity}/{operation}/not-found.json` 或 `validation-error.json` |
| 种子 SQL | `sql-data/init/data.sql` |
| 用例 SQL | `sql-data/cases/{case-name}.sql` |
| CLOB 文件 | `sql-data/cases/{case-name}-details.txt` |

***

## 3. Support 类参考（读源码，不内联）

Support 类是测试基础设施，**不要在 fixture 中复制其代码**，直接引用即可：

| 类 | 路径 | 职责 |
|----|------|------|
| `BaseE2ETest` | `e2e/support/BaseE2ETest.java` | 抽象基类：@SpringBootTest + @AutoConfigureMockMvc + MockMvc + Testcontainers MySQL + WireMock + @Sql 种子数据 + HTTP 辅助方法（`httpGetAndAssert`/`httpPostAndAssert`/`httpPutAndAssert`/`httpDeleteAndAssert`，封装 MockMvc perform + status 断言，返回响应体字符串） |
| `FixtureFileLoader` | `e2e/support/fixture/FixtureFileLoader.java` | 加载 classpath JSON 文件 + `${variable}` 模板变量替换 |
| `JsonAssert` | `support/json/JsonAssert.java` | json-unit 断言工具：`assertJsonEquals`（lenient，忽略额外字段和数组顺序）、`assertJsonEqualsStrict`（严格模式） |
| `DatabaseVerifier` | `support/sql/DatabaseVerifier.java` | JDBC 直接查询验证 DB 状态（count{Entities}, {entity}Exists, find{Entity}IdBy{Field} 等） |
| `MockFileLoader` | `support/mocks/MockFileLoader.java` | 加载 classpath `mock-data/` 目录下的 JSON 文件 + `${variable}` 模板变量替换，供 MockFactory 使用 |
| `{Service}MockFactory` | `support/mocks/` | WireMock stub 创建（`mock{Service}{Scenario}`），使用 MockFileLoader 加载 request/response 模板 |
| `{Service}MockVerifier` | `support/mocks/` | WireMock 调用验证（`verify{Service}{Action}`） |
| `@EnableSftpMock` | `support/sftp/` | 内嵌 SFTP 服务器注解（`@EnableSftpMock(sshPort = N)`），每个测试前启动 Apache Mina SSHD、后停止 |
| `SftpMockSupport` | `support/sftp/` | SFTP 文件验证工具：`verifyFileUploaded`、`verifyFileExists`、`verifyFileContent`、`verifyFileCount` 等 |

***

## 4. SQL 测试数据规则

| 规则 | 说明 |
|------|------|
| 三层结构 | Cleanup (`sql-data/cleanup/`) -> Init (`sql-data/init/`) -> Cases (`sql-data/cases/`) |
| Cleanup | `DELETE FROM` 按外键反序，不用 TRUNCATE |
| Init 种子 | 硬编码 ID（1-99），BCrypt 密码，显式时区时间戳 |
| Case 级别 | 特定场景额外数据，ID >= 100，用 `@Sql` 注解加载 |
| CLOB/TEXT | Testcontainers MySQL：直接内联字符串字面量；大文本可用 `#{}` 之外的常规 SQL 语法，无需 H2 的 `FILE_READ` 技巧（该技巧仅在 H2 降级场景使用，见 `db-conventions.md`） |
| 执行顺序 | BaseE2ETest @Sql -> method @Sql -> test body |

***

## 5. JSON Fixture 规则

| 规则 | 说明 |
|------|------|
| 目录 | `test-data/{entity}/{operation}/` |
| 请求模板 | 使用 `${variable}` 变量，运行时 `FixtureFileLoader.load(path, Map.of(...))` 替换 |
| 成功响应 | 可用模板变量，动态字段不写（由 `IGNORING_EXTRA_FIELDS` 自动忽略） |
| 错误响应 | 完全静态，不使用模板变量 |
| 动态字段占位符 | 可选：使用 `${json-unit.ignore}` 在 fixture 中显式标注忽略的字段 |
| 比较模式 | `assertJsonEquals` 使用 lenient 模式（忽略额外字段 + 数组顺序），fixture 可省略非关键字段 |

***

## 6. WireMock 命名模式

- **MockFactory 方法**: `mock{Service}{Scenario}` — 如 `mockPaymentAccepted()`、`mockPaymentRejected()`
- **MockVerifier 方法**: `verify{Service}{Action}` — 如 `verifyPaymentCalled(count)`、`verifyPaymentCalledWith(params...)`
- **静态文件**: `mock-data/mappings/{service}-{scenario}.json`，`__files/` 用 `.txt` 扩展名
- **请求匹配**: MockFactory 使用 `MockFileLoader` 加载 `mock-data/request/` 模板，通过 `equalToJson()` 匹配请求体结构
- **响应模板**: MockFactory 使用 `MockFileLoader` 加载 `mock-data/response/` 模板，支持 `${variable}` 变量替换

### MockFactory 模板加载模式

```java
@UtilityClass
public class {Service}MockFactory {

    /** 请求体模板 — 使用 ${json-unit.ignore} 通配匹配任意字段值 */
    private static final String REQUEST_BODY =
            MockFileLoader.load("request/{service}-{action}.json");

    public static void mock{Service}{Scenario}() {
        String responseBody = MockFileLoader.load("response/{service}-{scenario}.json");

        WireMock.stubFor(WireMock.post(urlEqualTo("/api/v1/{path}"))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(equalToJson(REQUEST_BODY))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody(responseBody)));
    }

    /** 带变量替换的响应模板 */
    public static void mock{Service}{Scenario}(String param) {
        String responseBody = MockFileLoader.load("response/{service}-{scenario}.json",
                Map.of("key", param));
        // ... 同上 stub 注册
    }
}
```

### mock-data 模板文件规则

| 目录 | 用途 | 占位符 |
|------|------|--------|
| `mock-data/request/` | 请求体匹配模板 | `${json-unit.ignore}`（通配任意值）、`${variable}`（匹配特定值） |
| `mock-data/response/` | 响应体返回模板 | `${variable}`（运行时替换动态值） |
| `mock-data/mappings/` | WireMock 静态映射 | 无占位符 |
| `mock-data/__files/` | WireMock 静态响应体 | 无占位符 |

**请求模板示例** (`mock-data/request/notification-user-created.json`)：

```json
{
  "userId": "${json-unit.ignore}",
  "username": "${json-unit.ignore}",
  "email": "${json-unit.ignore}",
  "eventType": "${json-unit.ignore}"
}
```

> WireMock 的 `equalToJson()` 底层使用 json-unit，因此支持 `${json-unit.ignore}` 占位符。

> 下游 Mock 的完整规范见 `downstream-conventions.md` §9。

***

## 7. 三层断言体系

| 层级 | 工具 | 验证目标 | 使用场景 |
|------|------|---------|---------|
| HTTP Response | JsonAssert (json-unit) | 响应 JSON 结构和字段值 | **每个测试必须** |
| Database | DatabaseVerifier + AssertJ | 数据库状态 | 创建/更新/删除操作 |
| Downstream | MockVerifier | 下游服务调用 | 有下游集成的操作 |
| SFTP File | SftpMockSupport | SFTP 文件上传验证 | 有文件上传的操作 |

***

## 7.1 SFTP Mock 模式

使用内嵌 Apache Mina SSHD 服务器模拟 SFTP 环境，适用于测试文件上传功能。

```java
@EnableSftpMock(sshPort = 2222)              // 类级别：启动内嵌 SFTP 服务器
public class UploadFlowTest extends BaseE2ETest {

    @Test
    void testUploadFile(Path tempSftpDir) {  // 参数注入：临时 SFTP 目录
        // Given
        String requestBody = FixtureFileLoader.load("upload/post/request.json",
                Map.of("dst", "dir/file.txt", "content", "hello"));

        // When
        httpPostAndAssert("/api/v1/uploads", commonHeadersAndJson(),
                requestBody, HttpStatus.OK);

        // Then — 验证文件已上传且内容正确
        SftpMockSupport.verifyFileUploaded(tempSftpDir, "dir/file.txt", "hello");
    }
}
```

**SftpMockSupport 常用方法：**

| 方法 | 用途 |
|------|------|
| `verifyFileUploaded(dir, path, content)` | 验证文件存在且内容匹配 |
| `verifyFileExists(dir, path)` | 仅验证文件存在 |
| `verifyFileNotExists(dir, path)` | 验证文件不存在 |
| `verifyFileContent(dir, path, content)` | 验证已存在文件的内容 |
| `verifyFileCount(dir, dirPath, count)` | 验证目录下文件数量 |
| `prepareFile(dir, path, content)` | 预置文件（用于下载测试） |

***

## 8. 测试方法模板

### 标准模板（Given/When/Then）

`httpXxxAndAssert` 由 `BaseE2ETest` 提供（封装 MockMvc `perform` + status 断言，返回响应体字符串）：

```java
import {base-package}.support.json.JsonAssert;  // 显式调用(@UtilityClass 工具类不用 static import,见 §10 Support 类扩展)

@Test
void testCreate{Entity}() {
    // Given
    String requestBody = FixtureFileLoader.load("{entity}/post/request.json",
            Map.of("{field1}", "value1", "{field2}", "value2"));
    {Service}MockFactory.mock{Service}{Scenario}();
    int initialCount = databaseVerifier.count{Entities}();

    // When
    String responseBody = httpPostAndAssert("/api/v1/{resources}",
            commonHeadersAndJson(), requestBody, HttpStatus.CREATED);

    // Then — 响应验证
    String expected = FixtureFileLoader.load("{entity}/post/created.json",
            Map.of("{field1}", "value1", "{field2}", "value2"));
    JsonAssert.assertJsonEquals(expected, responseBody);

    // And — 数据库状态验证
    assertThat(databaseVerifier.count{Entities}()).isEqualTo(initialCount + 1);

    // And — 下游调用验证
    {Service}MockVerifier.verify{Service}CalledWith("value1", "value2");
}
```

### 直接使用 MockMvc（需要精细断言时）

```java
@Autowired MockMvc mockMvc;

@Test
void testGet{Entity}ByIdNotFound() throws Exception {
    MvcResult result = mockMvc.perform(get("/api/v1/{resources}/99999")
                    .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("{ENTITY}_NOT_FOUND"))
            .andReturn();
}
```

### 错误场景模板

```java
@Test
void testCreate{Entity}WithValidationError() {
    // Given — 不需要 MockFactory（验证失败不触发下游）
    String requestBody = "{\"{field1}\":\"\",\"{field2}\":\"invalid\"}";

    // When
    String responseBody = httpPostAndAssert("/api/v1/{resources}",
            commonHeadersAndJson(), requestBody, HttpStatus.BAD_REQUEST);

    // Then — 静态 fixture，无模板变量
    String expected = FixtureFileLoader.load("{entity}/post/validation-error.json");
    JsonAssert.assertJsonEquals(expected, responseBody);

    // And — 下游未被调用
    {Service}MockVerifier.verify{Service}Called(0);
}
```

***

## 9. 配置规范

数据源不写死在 yml——由 `BaseE2ETest` 的 Testcontainers `@ServiceConnection`（MySQL）自动注入；yml 只保留非数据源配置（`application-test.yml`）：

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none          # 表结构由 Flyway 管理
    show-sql: true
  flyway:
    enabled: true
    locations: classpath:db/migration

wiremock:
  server:
    port: 0

app:
  downstream:
    {service}:
      base-url: http://localhost:${wiremock.server.port}
```

**BaseE2ETest 容器声明（示意）：**

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
public abstract class BaseE2ETest {

    @Container
    @ServiceConnection        // 自动注入 datasource url/username/password
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired protected MockMvc mockMvc;
    @Autowired protected DatabaseVerifier databaseVerifier;
}
```

> 单容器复用：`@Container` 标注在 `static` 字段 + 测试类继承同一基类时，Spring Test Context 缓存期间容器只启动一次；切勿在每个测试类重复声明容器。

***

## 10. Checklist

### 新增实体 E2E Test

- [ ] 测试类命名 `{Entity}FlowTest`，放在 `e2e/` 包，继承 `BaseE2ETest`
- [ ] 方法命名 `test{Action}{Entity}[{Condition}]`
- [ ] 每个方法包含 `// Given` / `// When` / `// Then` / `// And` 注释
- [ ] 请求体通过 `FixtureFileLoader.load()` + `Map.of()` 模板变量
- [ ] 响应断言使用 `JsonAssert.assertJsonEquals(expected, actual)`（**显式调用,非 static import** — @UtilityClass 兼容,见 §10）
- [ ] 写操作使用 `DatabaseVerifier` 验证 DB 状态
- [ ] 有下游调用时使用 `MockFactory` + `MockVerifier`
- [ ] MockFactory 使用 `MockFileLoader` 加载 request/response 模板（`mock-data/request/` 和 `mock-data/response/`）
- [ ] 请求模板使用 `equalToJson()` + `${json-unit.ignore}` 匹配请求体结构
- [ ] 所有 HTTP 调用通过 `httpXxxAndAssert()`（或 MockMvc `perform` + `@AutoConfigureMockMvc`）
- [ ] 种子数据已加入 `sql-data/init/data.sql`，清理 SQL 已更新 `sql-data/cleanup/clean-up.sql`
- [ ] JSON fixture 已创建在 `test-data/{entity}/` 下
- [ ] WireMock 请求/响应模板已创建在 `mock-data/request/` 和 `mock-data/response/` 下
- [ ] DatabaseVerifier 中添加了新实体的查询方法
- [ ] 有文件上传操作时使用 `@EnableSftpMock` + `SftpMockSupport`

### Support 类扩展

- [ ] 工具类用 `@UtilityClass`(统一规范见 `java-coding-standard.md` §5.2「工具类(@UtilityClass)」;main 与 test 一致),Spring Bean 用 `@Component` + `@RequiredArgsConstructor`
  - **调用必须用显式 `类名.方法`(如 `JsonAssert.assertJsonEquals(...)`、`FixtureFileLoader.load(...)`),禁止 `import static`** —— Lombok 生成的 static 方法与 javac static-import 不兼容(SB4 + Lombok 1.18.46 实测 test-compile 报 `cannot find symbol`)。规则 §8 模板已据此改用显式调用
- [ ] MockFactory: `mock{Service}{Scenario}`，使用 MockFileLoader 加载模板 | MockVerifier: `verify{Service}{Action}`
- [ ] DatabaseVerifier: `{verb}{Entity}{Field}` 或 `{verb}{Entity}By{Condition}`
