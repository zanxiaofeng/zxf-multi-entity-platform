---
paths:
  - "**/test/**/*.java"
  - "**/*FlowTest.java"
  - "**/*ContractTest.java"
---
# Testing Conventions

> **职责边界：** 本文件是测试规范**总则**——测试分层、包结构、单元测试模式、Spring Test Context 管理、测试独立性。e2e 测试详细编码规范（命名、Fixture、模板、Support 类、三层断言）见 `integration-test-guide.md`，契约测试见 `contract-test.md`，TDD 工作流见 `tdd-workflow.md`。

***

## 测试金字塔（对齐六边形分层）

| Layer | Package | Scope | Tools | DB | Downstream | Naming |
|-------|---------|-------|-------|-----|------------|--------|
| **Domain Unit** | `unit/domain/` | 领域模型纯逻辑 | JUnit 5 + AssertJ（零依赖） | None | None | `{ClassUnderTest}Test` |
| **Application Unit** | `unit/application/` | Service 用例编排 | JUnit 5 + Mockito（mock 出端口） | None | None | `{ClassUnderTest}Test` |
| **Integration** | `integration/` | 适配器 ↔ 真实基础设施 | `@DataJpaTest` / `@SpringBootTest` + Testcontainers (MySQL) | MySQL 容器 | None | `{AdapterUnderTest}Test` |
| **E2E** | `e2e/` | 完整请求链路 | `@SpringBootTest` + `@AutoConfigureMockMvc` + MockMvc | MySQL 容器 | WireMock | `{Entity}FlowTest` |
| **Contract** | `contract/` | API contract verification | Spring Cloud Contract + MockMvc + RestAssuredMockMvc | MySQL 容器 | RestAssuredMockMvc | `{ClassUnderTest}ContractTest` |

**Layer dependency rule:** A failing unit test points to a bug in a single class. A failing integration test points to an adapter/infrastructure issue. A failing e2e test points to a wiring/flow issue. Always fix from the bottom up.

**分层测试速度：** `unit/domain` 与 `unit/application` 零容器、毫秒级——六边形架构领域层零依赖的直接收益；容器只出现在 `integration` 与 `e2e`。

***

## Test Package Structure

测试位于 `src/test/java` 同构包下，按测试类型分包，共享工具放 `support/`：

```
src/test/java/{base-package}/
├── unit/                             # 纯逻辑测试（零 Spring context）
│   ├── domain/
│   │   ├── {Entity}Test.java         # 实体行为
│   │   └── PricingPolicyTest.java    # 值对象计算
│   └── application/
│       └── {Entity}ServiceTest.java  # 用例编排（Mockito mock port.out）
├── integration/                      # 适配器集成测试（Testcontainers MySQL）
│   ├── {Entity}RepositoryAdapterTest.java   # @DataJpaTest 切片 + @Import Adapter
│   └── KafkaEventPublisherTest.java         # 消息适配器（按需）
├── e2e/                              # 端到端测试（原 apitest/ 职责）
│   ├── {Entity}FlowTest.java       # MockMvc + WireMock
│   └── support/                      # e2e 专属基础设施
│       ├── BaseE2ETest.java          # Testcontainers + 公共配置
│       └── fixture/FixtureFileLoader.java
├── contract/                         # Contract tests
│   ├── ContractBaseTest.java
│   └── {Entity}ContractTest.java
└── support/                          # 跨测试类型共享的工具类
    ├── json/JsonAssert.java
    ├── mocks/{MockFileLoader, {Service}MockFactory, {Service}MockVerifier}.java
    └── sql/DatabaseVerifier.java
```

**包规则:**
- `unit/` — 纯 Mockito / JUnit，不启动 Spring context
- `integration/` — 适配器测试：`@DataJpaTest`（持久化切片）+ `@SpringBootTest`（需完整上下文的适配器），Testcontainers 真实 MySQL
- `e2e/` — 完整 Spring Boot 应用 + MockMvc 全链路验证，下游用 WireMock 打桩
- `contract/` — Spring Cloud Contract（MockMvc + RestAssuredMockMvc），验证 API 契约
- `support/` — 跨测试类型共享工具（`JsonAssert`、`MockFileLoader`、`DatabaseVerifier` 等）

***

## Unit Test Patterns

### Domain Tests — 纯 JUnit（零依赖）

```java
// unit/domain/PricingPolicyTest.java
class PricingPolicyTest {

    @Test
    void calculateFinalPrice_withQuantityAndDiscount() {
        PricingPolicy policy = new PricingPolicy(
            new BigDecimal("100.00"), new BigDecimal("0.10"));

        assertThat(policy.calculateFinalPrice(5))
            .isEqualByComparingTo(new BigDecimal("450.00"));   // BigDecimal 用 isEqualByComparingTo
    }

    @Test
    void constructor_rejectsNegativeBasePrice() {
        assertThatThrownBy(() -> new PricingPolicy(
            new BigDecimal("-1"), new BigDecimal("0.1")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
```

### Application Service Tests — Mockito（mock 出端口）

Use `@ExtendWith(MockitoExtension.class)` for service layer tests. No Spring context needed. **Mock 的是 `application/port/out` 接口，不是适配器实现。**

```java
@ExtendWith(MockitoExtension.class)
class {Entity}ServiceTest {

    @Mock
    private {Entity}Repository repository;      // port/out

    @Mock
    private EventPublisher eventPublisher;      // port/out

    @InjectMocks
    private {Entity}Service service;

    @Test
    void findById_existingId_returnsDto() {
        // given
        {Entity} entity = new {Entity}(new {Entity}Id(1L), "test");
        when(repository.findById(any())).thenReturn(Optional.of(entity));

        // when
        {Entity}Dto actual = service.findById("1");

        // then
        assertThat(actual.name()).isEqualTo("test");
    }

    @Test
    void findById_nonExistingId_throwsDomainException() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById("999"))
                .isInstanceOf({Entity}NotFoundException.class);
    }
}
```

### Adapter Unit Tests — Plain JUnit 5

静态映射器（`{Entity}PersistenceMapper`、`{Entity}WebMapper`）与工具类是纯函数，无需框架：

```java
class {Entity}PersistenceMapperTest {

    @Test
    void toEntity_mapsAllFields() {
        {Entity} entity = new {Entity}(new {Entity}Id(null), "test");

        {Entity}JpaEntity jpaEntity = {Entity}PersistenceMapper.toEntity(entity);

        assertThat(jpaEntity.getName()).isEqualTo("test");
        assertThat(jpaEntity.getId()).isNull(); // not persisted yet
    }

    @Test
    void toDomain_rebuildsInvariants() {
        {Entity}JpaEntity jpaEntity = new {Entity}JpaEntity();
        jpaEntity.setId(1L);
        jpaEntity.setName("test");

        {Entity} domain = {Entity}PersistenceMapper.toDomain(jpaEntity);

        assertThat(domain.getId()).isEqualTo(new {Entity}Id(1L));
    }
}
```

***

## Integration Tests — Testcontainers（真实 MySQL）

### Repository Adapter Tests — @DataJpaTest 切片

只加载持久化切片，`@Import` 被测适配器：

```java
// integration/{Entity}RepositoryAdapterTest.java
@DataJpaTest
@Import({Entity}RepositoryAdapter.class)
@Testcontainers
class {Entity}RepositoryAdapterTest {

    @Container
    @ServiceConnection          // SB 3.1+/4：自动装配数据源连接信息，免 @DynamicPropertySource
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired  // 测试切片允许字段注入(Spring Test 业内惯例);生产代码必须构造器注入(见 java-coding-standard.md §1.5)
    private {Entity}Repository repository;          // application/port/out 接口

    @Autowired
    private {Entity}JpaRepository jpaRepository;   // Spring Data

    @Test
    void save_andFindById_roundTrip() {
        {Entity} entity = new {Entity}(new {Entity}Id(null), "test");
        {Entity} saved = repository.save(entity);

        Optional<{Entity}> found = repository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("test");
    }
}
```

### 全上下文适配器测试 — @SpringBootTest + Testcontainers

需要完整上下文的消息/外部适配器（如 `KafkaEventPublisher`）用 `@SpringBootTest` + 对应 Testcontainers 模块。

***

## Spring Test Context Management

### Test-Specific Beans with @TestConfiguration

```java
@TestConfiguration
class TestConfig {
    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }
}
```

### Context Caching Rules

Spring Test caches the `ApplicationContext` by configuration. Breaking the cache causes full reloads (slow).

**Spring Boot 4 测试 Mock 变化（重要）：**

- `@MockBean` / `@SpyBean` 已移除 → 改用 `@MockitoBean` / `@MockitoSpyBean`
- `@MockitoBean` 可用于测试类字段（含超类层级），但**不能用于 `@Configuration` 类**；共享 mock 改用 `@MockitoBean(types = {...})` 或自定义复合注解
- `MockitoTestExecutionListener` 已移除 → 用 Mockito 原生 `@ExtendWith(MockitoExtension.class)`
- **`@SpringBootTest` 不再自动注入 MockMvc** → e2e 测试必须加 `@AutoConfigureMockMvc`
- **`@SpringBootTest` 不再自动注入 `WebClient` / `TestRestTemplate`** → 需 `@AutoConfigureTestRestTemplate`（并依赖 `spring-boot-resttestclient`）；新代码推荐 `RestTestClient` + `@AutoConfigureRestTestClient`
- For service unit tests, prefer `@ExtendWith(MockitoExtension.class)` over `@MockitoBean`
- Reserve `@MockitoBean` for e2e/integration tests where you must replace a bean inside the running context

**Avoid `@DirtiesContext` unless truly necessary:**

- `@DirtiesContext` destroys the cached context after the test class
- Acceptable: tests that modify shared singleton state (e.g., `@CacheManager`, `JsonMapper` config)
- Not acceptable: just to reset data — use `@Sql` cleanup scripts instead

**Best practice for fast test suites:**
1. Group tests with the same `@MockitoBean` configuration into the same test class
2. Use `@Sql` for data cleanup instead of `@DirtiesContext`
3. Keep e2e test classes focused on a single controller/flow to share the same context

***

## Test Independence Rules

### Mandatory Rules

1. **No `@DependsOn` or test ordering dependencies** — every test must pass when run alone or in any order
2. **No shared mutable state between tests** — use local variables or `@Sql` for setup
3. **Deterministic test data** — use fixed values, not `System.currentTimeMillis()` or `UUID.randomUUID()`
4. **No hidden test coupling** — a test must not rely on side effects from a previous test

### Data Isolation with @Sql

Each test initializes and cleans up its own data:

```java
@Test
@Sql(scripts = {
    "/sql-data/cleanup/cleanup-{entity}.sql",
    "/sql-data/init/data.sql"
})
void create_{entity}_returns201() {
    // test body
}
```

**@Sql script rules:**
- Cleanup scripts run BEFORE init scripts (delete residual data)
- Init scripts insert deterministic test data
- Case-level scripts for edge cases (e.g., `/sql-data/cases/{entity}-conflict.sql`)

### Time Determinism

For tests involving time, inject a `Clock` bean:

```java
// Production
@Bean
public Clock systemClock() {
    return Clock.systemDefaultZone();
}

// Test
@TestConfiguration
static class TestClockConfig {
    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-01-15T10:30:00Z"), ZoneOffset.UTC);
    }
}
```

Never use `OffsetDateTime.now()` in test assertions — always use the fixed clock value.

***

## Comprehensive E2E Test Reference

For e2e test conventions including naming, fixtures, templates, support class reference, WireMock patterns, assertion system, and checklists, see `integration-test-guide.md`.
