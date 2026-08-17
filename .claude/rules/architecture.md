---
paths:
  - "**/domain/**/*.java"
  - "**/application/**/*.java"
  - "**/infrastructure/**/*.java"
  - "**/interfaces/**/*.java"
---
# 四层架构规范

基于六边形架构（Hexagonal / Ports & Adapters）与领域驱动设计（DDD）的四层架构最佳实践。适用于 Spring Boot 4.x + JPA（Hibernate 7）项目。

> **职责边界：** 本文件定义**分层规则、包结构、各层职责概述、跨领域关注点、反模式**。各层的详细编码规范见对应专题文件（见文末导航表）。

***

## 1. 分层与依赖规则

```
                       ┌───────────────────────────────────┐
                       │  Interfaces  (HTTP 入口)            │
                       │  Controller, ExceptionHandler       │
                       └─────────────┬─────────────────────┘
                                     │ 依赖
                       ┌─────────────▼─────────────────────┐
                       │  Application  (业务编排)             │
                       │  Service, DTO, Mapper               │
                       └─────────────┬─────────────────────┘
                                     │ 依赖
                       ┌─────────────▼─────────────────────┐
                       │  Domain  (核心业务)                  │
                       │  Entity, VO, Port, DomainException  │
                       └───────────────────────────────────┘
                                     ▲ 依赖
                       ┌─────────────┴─────────────────────┐
                       │  Infrastructure  (技术实现)          │
                       │  Adapter, ClientImpl, Config        │
                       └───────────────────────────────────┘
```

**依赖规则（必须严格遵守）：**

| 层 | 允许依赖 | 禁止依赖 |
|----|---------|---------|
| Domain | JDK, Jakarta Persistence, Spring Data types (Page/Pageable), Lombok | application, infrastructure, interfaces |
| Application | domain | infrastructure, interfaces |
| Infrastructure | domain | application, interfaces |
| Interfaces | application, domain (ErrorCode, BusinessException only) | infrastructure |

> **核心原则：** Domain 是最内层，不依赖任何业务层。Infrastructure 和 Application 都依赖 Domain 的接口（Port），Domain 不知道谁在使用它。

***

## 2. 包结构

```
com.example.{project}
├── {Project}Application.java
├── domain/
│   ├── common/                         # BusinessException, ErrorCode
│   ├── {entity}/
│   │   ├── {Entity}.java               # 聚合根 / JPA Entity
│   │   ├── {Entity}Id.java             # 标识符 VO (可选)
│   │   ├── {Entity}Status.java         # 状态枚举
│   │   └── {Entity}Repository.java     # Repository Port (纯 Java 接口)
│   ├── {entity}/vo/                    # Value Objects (可选)
│   │   └── Email.java                  # 封装校验规则的不可变值对象
│   └── downstream/
│       └── {ServiceName}Client.java    # 下游服务 Port (纯 Java 接口)
├── application/
│   └── {entity}/
│       ├── dto/
│       │   ├── Create{Entity}Request.java
│       │   ├── Update{Entity}Request.java
│       │   ├── {Entity}Query.java      # 查询条件 DTO
│       │   └── {Entity}Response.java
│       ├── mapper/
│       │   └── {Entity}Mapper.java
│       └── {Entity}Service.java        # 具体 class（按需抽接口）
├── infrastructure/
│   ├── config/
│   ├── persistence/
│   │   ├── {Entity}JpaRepository.java  # Spring Data JPA
│   │   └── {Entity}JpaAdapter.java     # 实现 domain Repository Port
│   └── downstream/
│       └── {ServiceName}ClientImpl.java
└── interfaces/
    ├── common/
    │   ├── ApiResponse.java
    │   └── GlobalExceptionHandler.java
    └── {entity}/
        └── {Entity}Controller.java
```

**命名约定：**

| 类型 | 命名模式 | 示例 |
|------|---------|------|
| Entity | `{名词}` | `User`, `Order` |
| Repository Port | `{Entity}Repository` | `UserRepository` |
| JPA Repository | `{Entity}JpaRepository` | `UserJpaRepository` |
| JPA Adapter | `{Entity}JpaAdapter` | `UserJpaAdapter` |
| Service | `{Entity}Service` | `UserService`（默认具体 class，多实现时抽接口） |
| 请求 DTO | `Create/Update{Entity}Request` | `CreateUserRequest` |
| 响应 DTO | `{Entity}Response` | `UserResponse` |
| 查询 DTO | `{Entity}Query` | `UserQuery` |
| Mapper | `{Entity}Mapper` | `UserMapper` |
| Controller | `{Entity}Controller` | `UserController` |
| 下游 Port | `{Service}Client` | `NotificationClient` |
| 下游 Impl | `{Service}ClientImpl` | `NotificationClientImpl` |
| Value Object | 业务名词 | `Email`, `Money`, `Address` |
| ErrorCode | `{模块编号}{错误编号}` | `001001` (用户模块 001, 错误 001) |

***

## 3. Domain 层（架构核心）

Domain 层是架构核心。所有业务规则、业务术语、业务异常都定义在此层。此层不依赖 Spring Framework（JPA 注解是为简化持久化的务实妥协）。

### 3.1 Entity（聚合根）

Entity 是具有唯一标识的业务对象，应包含业务行为（方法），而非贫血数据袋。

**关键规则：**

| 规则 | 说明 |
|------|------|
| **`@Version` 必须** | 所有可变实体必须添加 `@Version`，防止并发更新丢失 |
| **时间戳用 `@PrePersist` / `@PreUpdate`** | 不依赖 `@Builder.Default`（见 §7.1） |
| **equals/hashCode 基于 id** | 未持久化实体（id 为 null）用 `getClass().hashCode()` 避免冲突 |
| **领域方法替代 setter** | 状态变更通过 `activate()` / `deactivate()` 等意图明确的方法 |
| **构造器保护** | `@NoArgsConstructor(access = PROTECTED)` + `@AllArgsConstructor(access = PRIVATE)`，强制通过 Builder 或工厂创建 |
| **枚举禁止 ORDINAL** | 必须 `@Enumerated(EnumType.STRING)`（数据库可读性 + 枚举重排序安全性） |

> Entity 完整模板与 Lombok 注解规范见 `db-conventions.md`。

### 3.2 Value Object

当字段有内在校验规则时，封装为 Value Object。判断标准：**如果一段校验逻辑出现在两个以上的 DTO 或方法中，就应该提取为 VO。**

- **使用 VO 的场景：** 有格式校验（Email、Phone）、有业务运算（Money、Percentage）、有多字段组合（Address）、在多个 DTO 间重复相同校验
- **不使用 VO 的场景：** 简单字符串/数值、仅在单个 DTO 中使用
- **Java 21 落地：** 非持久化 VO 首选 `record`；JPA 持久化 VO 用 `@Embeddable`

> VO 完整示例与 OO 设计约束见 `java-object-calisthenics.md` §2.3。

### 3.3 Repository Port（接口）

纯 Java 接口，无 Spring 注解。定义领域视角的数据访问契约。

```java
public interface {Entity}Repository {
    {Entity} save({Entity} entity);
    Optional<{Entity}> findById(Long id);
    void deleteById(Long id);
    Optional<{Entity}> findByName(String name);
    boolean existsByName(String name);
    Page<{Entity}> findAll(Pageable pageable);
}
```

**命名约定：**
- 查询方法：`findBy{Field}` / `existsBy{Field}`
- 返回单个：`Optional<T>`，禁止返回 null
- 返回集合：`List<T>` 或 `Page<T>`

### 3.4 下游服务 Port（接口）

```java
public interface {ServiceName}Client {
    boolean sendNotification({EventName}Event event);
}
```

**规则：**
- 方法参数使用事件对象或 DTO，禁止超过 3 个原始参数
- 返回值反映调用结果：`boolean`（成功/失败）或 `T`（需要响应数据）
- 方法名表达业务意图，非技术操作

> 下游完整实现规范见 `downstream-conventions.md`。

### 3.5 异常体系

全项目只有一个业务异常基类 `BusinessException`（domain/common/）和一个 `ErrorCode` 枚举（domain/common/）。

> 异常体系完整定义、抛出/捕获规范、全局处理见 `exception-handling.md`。

### 3.6 Domain Service（按需）

当业务规则跨多个聚合或无法自然归属于单个 Entity 时，使用 Domain Service。

**判断标准：** 只涉及单个聚合内部状态 → Entity 方法；涉及多个聚合、需要查询外部数据、跨聚合一致性 → Domain Service。

***

## 4. Application 层

Application 层负责业务编排：接收 DTO → 调用 Domain → 返回 DTO。**不包含业务规则**，业务规则在 Domain 层。

> Service 写法、事务管理、DTO 映射、乐观锁处理、方法命名等完整规范见 `service-conventions.md`。

**核心要点：**
- Application Service 默认用**具体 `@Service` class**（避免无意义的接口/Impl 拆分）
- `@Transactional(readOnly = true)` 类级别，写操作用 `@Transactional` 覆盖
- DTO 全部使用 `record`，请求带 Bean Validation 注解，响应不带
- Mapper 用 `@Component` + 手动映射，不用 MapStruct
- 禁止在事务方法内做耗时的下游调用

***

## 5. Infrastructure 层

Infrastructure 层实现 Domain 层定义的 Port 接口。所有技术细节（JPA、HTTP、配置）都封装在此层。

### 5.1 Repository 实现（两类文件）

```java
// 1. Spring Data JPA 接口
@Repository
public interface {Entity}JpaRepository extends JpaRepository<{Entity}, Long> { ... }

// 2. Adapter — 桥接 Domain Port 到 Spring Data JPA
@Component
@RequiredArgsConstructor
public class {Entity}JpaAdapter implements {Entity}Repository { ... }
```

**为什么要两层：** `JpaRepository` 是 Spring Data 技术选型，`JpaAdapter` 隔离技术细节，Application 层只依赖 Domain 的 Port 接口。

### 5.2 下游服务实现

> 下游 HTTP 客户端实现、错误分类、弹性模式、连接池配置见 `downstream-conventions.md`。

### 5.3 Config

> RestClient/RestTemplate Bean 配置、超时设置见 `downstream-conventions.md`。

***

## 6. Interfaces 层

Interfaces 层只做 HTTP 协议转换：HTTP Request → Java 调用 → HTTP Response。**零业务逻辑。**

> URL 模式、HTTP 方法语义、状态码映射、分页约定、`@PathVariable` 校验等完整规范见 `api-conventions.md`。

**核心要点：**
- 所有端点返回 `ResponseEntity<ApiResponse<T>>`（DELETE 除外：返回 **204 No Content**，无响应体）
- 用 `@Valid` 触发 Bean Validation，不做手动校验
- 不做任何业务判断（if/else、业务异常抛出、数据转换）
- 全局异常处理统一在 `GlobalExceptionHandler`（`@RestControllerAdvice`），Controller 不写 try-catch

> 异常处理完整规范见 `exception-handling.md`。

***

## 7. 跨领域关注点

### 7.1 审计（时间戳）

标准做法：`@PrePersist` / `@PreUpdate` 生命周期回调，不依赖 `@Builder.Default`、无需额外配置：

```java
@PrePersist
protected void onCreate() { createdAt = OffsetDateTime.now(); }

@PreUpdate
protected void onUpdate() { updatedAt = OffsetDateTime.now(); }
```

> 仅当需要记录「谁创建/谁修改」（auditor）时才引入 Spring Data JPA Auditing（`@CreatedDate` / `@LastModifiedDate` + `@CreatedBy` / `@LastModifiedBy` + `@EnableJpaAuditing`）；纯时间戳场景不必引入。

### 7.2 乐观锁（必须）

**所有可变实体必须添加 `@Version`：**

```java
@Version
private Long version;
```

JPA 自动处理：更新时检查 version，不匹配抛出 `OptimisticLockingFailureException`。

> Service 层乐观锁异常处理见 `service-conventions.md` §5。

### 7.3 Domain Event（推荐）

用事件解耦副作用，避免在 Service 中直接调用下游：

```java
// Domain 层定义事件
public record {Entity}CreatedEvent(Long id, String name) {}

// Application 层发布事件（事务内）
eventPublisher.publishEvent(new {Entity}CreatedEvent(saved.getId(), saved.getName()));

// Infrastructure 层监听，事务提交后执行
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onCreated({Entity}CreatedEvent event) { client.sendNotification(event); }
```

**好处：** Service 不依赖下游 Client，下游调用在事务外执行，新增副作用只需新增 Listener（开闭原则）。

### 7.4 软删除（按需）

```java
@Column(name = "deleted_at")
private OffsetDateTime deletedAt;

// Hibernate 7 使用 @SQLRestriction 替代已废弃的 @Where
@SQLRestriction("deleted_at IS NULL")
@Entity
public class {Entity} { ... }

public void softDelete() { this.deletedAt = OffsetDateTime.now(); }
```

### 7.5 分页安全

Controller 应限制 Pageable 的最大页面大小 — 通过全局配置实现（`@PageableDefault` 没有 `maxPageSize` 属性）：

```yaml
spring:
  data:
    web:
      pageable:
        max-page-size: 100
        default-page-size: 20
```

> 分页 API 规范详见 `api-conventions.md` Pagination Conventions。

***

## 8. 反模式（禁止）

| # | 反模式 | 为什么有问题 | 正确做法 |
|---|-------|------------|---------|
| 1 | **贫血 Entity**：只有 getter/setter 无行为 | 业务逻辑散落在 Service，Entity 退化为数据结构 | 用领域方法封装状态变更 |
| 2 | **Controller 包含业务逻辑** | 违反单一职责，难以测试 | Controller 只做 HTTP↔Java 转换 |
| 3 | **Service 直接注入 JpaRepository** | 绕过 Domain Port，破坏六边形架构 | 注入 Domain Repository 接口 |
| 4 | **事务内做耗时下游调用** | 持有数据库连接和事务锁，影响性能和一致性 | 用 Domain Event + `@TransactionalEventListener(AFTER_COMMIT)` |
| 5 | **ErrorCode 单体枚举无限膨胀** | 所有模块的错误码混在一起，难以维护 | 按模块编号分段：`{模块号}{序号}` |
| 6 | **GlobalExceptionHandler 硬编码实体错误码** | 新增实体后，`DataIntegrityViolation` 处理会返回错误实体的错误码 | 通用错误用通用 ErrorCode |
| 7 | **skip `@Version`** | 并发更新丢失数据 | 所有可变实体必须 `@Version` |
| 8 | **枚举用 ORDINAL 持久化** | 数据库值无意义，枚举重排序导致数据错乱 | 必须用 `@Enumerated(STRING)` |
| 9 | **字段注入 `@Autowired`** | 隐藏依赖、难以测试 | 构造器注入 `@RequiredArgsConstructor` |
| 10 | **下游 Client 方法超过 3 个原始参数** | 可读性差，参数顺序易出错 | 封装为事件对象或 DTO |
| 11 | **密码/加密等策略分散在多处** | 策略不一致，改一处漏一处 | 统一在 Mapper 或 Domain Service 中处理 |
| 12 | **Mapper 返回 Entity** | 泄露 Domain 对象到上层 | Mapper 只做 Entity ↔ DTO 转换 |
| 13 | **Repository 返回 DTO** | Repository 是 Domain 层组件，不应知道 DTO | Repository 只操作 Entity |
| 14 | **用 `@Builder.Default` 设置时间戳** | 只在使用 Builder 时生效，其他创建方式会丢失默认值 | 用 `@PrePersist` / JPA Auditing |

***

## 9. 测试架构

> 测试分层、包结构、命名约定、数据隔离等完整规范见 `test-conventions.md` 和 `integration-test-guide.md`。

```
┌─────────────────────────────────────────────────────────────────┐
│  API Test (Integration) — {Entity}ApiTests extends BaseApiTest   │
│  Spring Boot RANDOM_PORT + WebTestClient + H2 + WireMock        │
├─────────────────────────────────────────────────────────────────┤
│  Contract Test — {Entity}ContractTest                            │
│  Spring Cloud Contract + MockMvc + RestAssuredMockMvc           │
├─────────────────────────────────────────────────────────────────┤
│  Unit Test — {ClassUnderTest}Test                                │
│  JUnit 5 + Mockito (no Spring Context)                          │
└─────────────────────────────────────────────────────────────────┘
```

***

## 10. 新增业务模块 Checklist

以 `{Entity} = Order` 为例：

**Phase 1 — Domain（先定义契约）**
- [ ] `domain/order/Order.java` — Entity + `@Version` + 领域方法
- [ ] `domain/order/OrderStatus.java` — 状态枚举
- [ ] `domain/order/OrderRepository.java` — Port 接口
- [ ] `domain/common/ErrorCode.java` — 追加 Order 错误码

**Phase 2 — Infrastructure（实现技术细节）**
- [ ] `infrastructure/persistence/OrderJpaRepository.java`
- [ ] `infrastructure/persistence/OrderJpaAdapter.java`
- [ ] `db/migration/V{N}__create_orders_table.sql` — Flyway

**Phase 3 — Application（编排业务）**
- [ ] `application/order/dto/CreateOrderRequest.java` + `UpdateOrderRequest.java` + `OrderResponse.java`
- [ ] `application/order/mapper/OrderMapper.java`
- [ ] `application/order/OrderService.java`（具体 class）

**Phase 4 — Interfaces（暴露 HTTP）**
- [ ] `interfaces/order/OrderController.java`

**Phase 5 — Test**
- [ ] `unit/OrderServiceTest.java` + `unit/OrderMapperTest.java` — 单元测试
- [ ] `integration/OrderApiTests.java` + JSON fixtures + @Sql seed data
- [ ] `contracts/orders/` — Spring Cloud Contract

***

## 专题文件导航

| 主题 | 文件 |
|------|------|
| 技术栈与版本 | `tech-stack.md` |
| Service 层完整规范 | `service-conventions.md` |
| API 设计规范 | `api-conventions.md` |
| 异常处理完整规范 | `exception-handling.md` |
| 参数校验规范 | `validation.md` |
| Java 编码规范 | `java-coding-standard.md` |
| 对象健身操 | `java-object-calisthenics.md` |
| 日志规范 | `logging.md` |
| 数据库规范 | `db-conventions.md` |
| 数据库迁移 | `db-migration.md` |
| 下游集成规范 | `downstream-conventions.md` |
| 测试规范总则 | `test-conventions.md` |
| API Test 指南 | `integration-test-guide.md` |
| 契约测试 | `contract-test.md` |
| TDD 工作流 | `tdd-workflow.md` |
| Code Review | `code-review.md` |
