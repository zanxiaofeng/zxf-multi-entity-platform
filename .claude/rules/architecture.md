---
paths:
  - "**/domain/**/*.java"
  - "**/application/**/*.java"
  - "**/infrastructure/**/*.java"
  - "**/interfaces/**/*.java"
---
# 四层架构规范

基于六边形架构（Hexagonal / Ports & Adapters）与领域驱动设计（DDD）的四层架构最佳实践。适用于 Spring Boot 4.x + JPA（Hibernate 7）项目。

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

## 3. Domain 层

Domain 层是架构核心。所有业务规则、业务术语、业务异常都定义在此层。此层不依赖 Spring Framework（JPA 注解是为简化持久化的务实妥协）。

### 3.1 Entity（聚合根）

Entity 是具有唯一标识的业务对象。应包含业务行为（方法），而非贫血数据袋。

```java
@Entity
@Table(name = "{table_name}")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(of = {"id", "{key_field}"})
public class {Entity} {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 必须字段：nullable = false + 业务约束
    @Column(nullable = false, unique = true, length = 50)
    private String name;

    // 枚举：必须 STRING 持久化，禁止 ORDINAL
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private {Entity}Status status = {Entity}Status.ACTIVE;

    // 乐观锁：所有可变实体必须有
    @Version
    private Long version;

    // 时间戳：OffsetDateTime，不用 Date/LocalDateTime
    @Column(updatable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    // ── 生命周期回调 ──

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // ── 领域方法：封装业务规则 ──

    public void rename(String newName) {
        Assert.hasText(newName, "name must not be blank");
        this.name = newName;
    }

    public void activate() {
        this.status = {Entity}Status.ACTIVE;
    }

    public void deactivate(String reason) {
        Assert.hasText(reason, "deactivation reason must not be blank");
        this.status = {Entity}Status.INACTIVE;
    }

    // ── equals/hashCode：基于 id (JPA Identity Pattern) ──

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof {Entity} that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
```

**关键规则：**
- **必须有 `@Version`** — 防止并发更新丢失，所有可变实体强制
- **时间戳用 `@PrePersist` / `@PreUpdate`** — 不依赖 `@Builder.Default`
- **equals/hashCode 基于 id** — 未持久化实体（id 为 null）用 `getClass().hashCode()` 避免冲突
- **领域方法替代 setter** — 状态变更通过 `activate()` / `deactivate()` 等意图明确的方法
- **构造器保护** — `@NoArgsConstructor(access = PROTECTED)` + `@AllArgsConstructor(access = PRIVATE)`，强制通过 Builder 或工厂创建
- **枚举禁止 ORDINAL** — 数据库可读性 + 枚举重排序安全性

### 3.2 Value Object

当字段有内在校验规则时，封装为 Value Object。判断标准：**如果一段校验逻辑出现在两个以上的 DTO 或方法中，就应该提取为 VO。**

```java
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Email {
    private static final Pattern PATTERN = Pattern.compile("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$");

    @Column(name = "email", nullable = false)
    private String value;

    public Email(String value) {
        Assert.hasText(value, "email must not be blank");
        Assert.isTrue(PATTERN.matcher(value).matches(),
            () -> "invalid email format: " + value);
        this.value = value;
    }

    // VO 持久化为 String，但在 domain 层提供类型安全
}
```

**使用 VO 的场景：**
- 有格式校验（Email、Phone、PostalCode）
- 有业务运算逻辑（Money、Percentage、Duration）
- 有多字段组合（Address = street + city + zip）
- 在多个 DTO 间重复相同的校验规则

**不使用 VO 的场景：**
- 简单字符串/数值，无特殊校验
- 仅在单个 DTO 中使用

### 3.3 Repository Port（接口）

纯 Java 接口，无 Spring 注解。定义领域视角的数据访问契约。

```java
public interface {Entity}Repository {
    // 基础 CRUD
    {Entity} save({Entity} entity);
    Optional<{Entity}> findById(Long id);
    void deleteById(Long id);

    // 业务查询
    Optional<{Entity} findByName(String name);
    boolean existsByName(String name);

    // 分页
    Page<{Entity}> findAll(Pageable pageable);

    // 动态条件查询（可选，需要时添加）
    Page<{Entity}> findByCondition({Entity}Query condition, Pageable pageable);
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

### 3.5 异常体系

```java
// domain/common/BusinessException.java
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Object[] args;

    public BusinessException(ErrorCode errorCode, Object... args) {
        super(errorCode.getDefaultMessage(), null, true, false);
        this.errorCode = errorCode;
        this.args = args;
    }
}

// domain/common/ErrorCode.java
@Getter @RequiredArgsConstructor
public enum ErrorCode {
    // 通用错误 (000xxx)
    SUCCESS("SUCCESS", "Success", HttpStatus.OK),
    INTERNAL_ERROR("000001", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
    VALIDATION_ERROR("000002", "Validation failed", HttpStatus.BAD_REQUEST),

    // {模块}错误 ({模块号}xxxx)
    USER_NOT_FOUND("001001", "User not found", HttpStatus.NOT_FOUND),
    USER_ALREADY_EXISTS("001002", "User already exists", HttpStatus.CONFLICT),

    // 编码规则：{模块编号}{错误序号}
    // 000 = 通用, 001 = 用户, 002 = 订单, ...
    ;

    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}
```

### 3.6 Domain Service（按需）

当业务规则跨多个聚合或无法自然归属于单个 Entity 时，使用 Domain Service。

```java
// 判断标准：逻辑涉及多个聚合，或需要 Repository 查询来验证
@Component
@RequiredArgsConstructor
public class {Entity}DomainService {
    private final {Entity}Repository repository;

    public void checkUniqueness(String field, String value) {
        if (repository.existsByField(value)) {
            throw new BusinessException(ErrorCode.{ENTITY}_ALREADY_EXISTS, value);
        }
    }
}
```

**何时使用 Domain Service vs Entity 方法：**
- 只涉及单个聚合内部状态 → Entity 方法
- 涉及多个聚合、需要查询外部数据、跨聚合一致性 → Domain Service

***

## 4. Application 层

Application 层负责业务编排：接收 DTO → 调用 Domain → 返回 DTO。**不包含业务规则**，业务规则在 Domain 层。

### 4.1 Service（具体 class，按需抽接口）

Application Service 默认用**具体 `@Service` class**（简洁，避免无意义的接口/Impl 拆分）。仅当存在多实现、策略模式、或需为不同调用方/下游模块提供稳定契约时，才抽取接口。

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)          // 类级别：默认只读
public class {Entity}Service {
    private final {Entity}Repository repository;
    private final {Entity}Mapper mapper;
    // 下游客户端：按需注入
    // Domain Service：按需注入

    @Transactional                       // 写操作覆盖为读写
    public {Entity}Response create(Create{Entity}Request request) {
        // 1. 业务规则校验（委托 Domain Service 或直接校验）
        // 2. DTO → Entity（通过 Mapper）
        // 3. 持久化
        // 4. 下游通知（如有）
        // 5. Entity → Response DTO（通过 Mapper）
    }

    public {Entity}Response findById(Long id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .orElseThrow(() -> new BusinessException(ErrorCode.{ENTITY}_NOT_FOUND, id));
    }
}
```

> **何时抽接口：** 多实现（按 profile/配置选择不同实现）、策略模式、或为下游模块提供稳定契约。**单实现 Service 不要为“模式”强抽接口**——属过度设计（Mockito 可直接 mock 具体类；Domain Port 已解耦 infrastructure，application service 对 controller 无需再套一层接口）。

**事务管理规则：**
- `@Transactional(readOnly = true)` 放在类级别 — 性能优化 + 语义清晰
- 写操作（create/update/delete）用 `@Transactional` 覆盖
- **禁止在事务方法内做耗时的下游调用** — 事务内只做数据库操作，下游调用放在事务提交后

### 4.2 DTO

```java
// 创建请求：所有必填字段带 Validation
public record Create{Entity}Request(
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 50, message = "Name must be 2-50 characters")
    String name,
    @NotNull(message = "Type is required")
    {Entity}Type type
) {}

// 更新请求：字段可选（null 表示不更新）
public record Update{Entity}Request(
    @Size(min = 2, max = 50, message = "Name must be 2-50 characters")
    String name,
    @Email(message = "Must be a valid email")
    String email
) {}

// 响应 DTO：无 Validation 注解
public record {Entity}Response(
    Long id,
    String name,
    {Entity}Status status,
    OffsetDateTime createdAt
) {}

// 查询条件 DTO：所有字段可选
public record {Entity}Query(
    String name,
    {Entity}Status status,
    OffsetDateTime createdAfter,
    OffsetDateTime createdBefore
) {}
```

**DTO 规则：**
- 全部使用 `record`
- 请求 DTO 带 Bean Validation 注解，响应 DTO 不带
- Create 的必填字段用 `@NotBlank` / `@NotNull`，Update 的字段可选（null = 不更新）
- 查询 DTO 所有字段可选

### 4.3 Mapper

```java
@Component
@RequiredArgsConstructor
public class {Entity}Mapper {

    public {Entity} toEntity(Create{Entity}Request request) {
        return {Entity}.builder()
                .name(request.name())
                .build();
    }

    public {Entity}Response toResponse({Entity} entity) {
        return new {Entity}Response(
                entity.getId(),
                entity.getName(),
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }

    // 分页转换
    public Page<{Entity}Response> toResponsePage(Page<{Entity}> page) {
        return page.map(this::toResponse);
    }
}
```

**Mapper 规则：**
- `@Component`，不用 MapStruct 等框架
- 手动映射，显式且可追踪
- 跨层转换只在此发生（Entity ↔ DTO）

***

## 5. Infrastructure 层

Infrastructure 层实现 Domain 层定义的 Port 接口。所有技术细节（JPA、HTTP、配置）都封装在此层。

### 5.1 Repository 实现（两类文件）

```java
// 1. Spring Data JPA 接口
@Repository
public interface {Entity}JpaRepository extends JpaRepository<{Entity}, Long> {
    Optional<{Entity}> findByName(String name);
    boolean existsByName(String name);

    // 复杂查询用 @Query
    @Query("SELECT e FROM {Entity} e WHERE e.status = :status")
    Page<{Entity}> findByStatus(@Param("status") {Entity}Status status, Pageable pageable);
}

// 2. Adapter — 桥接 Domain Port 到 Spring Data JPA
@Component
@RequiredArgsConstructor
public class {Entity}JpaAdapter implements {Entity}Repository {
    private final {Entity}JpaRepository jpaRepository;

    @Override
    public {Entity} save({Entity} entity) { return jpaRepository.save(entity); }

    @Override
    public Optional<{Entity}> findById(Long id) { return jpaRepository.findById(id); }

    @Override
    public boolean existsByName(String name) { return jpaRepository.existsByName(name); }

    @Override
    public Page<{Entity}> findAll(Pageable pageable) { return jpaRepository.findAll(pageable); }
}
```

**为什么要两层：**
- `JpaRepository` 是 Spring Data 技术选型，未来可能替换
- `JpaAdapter` 隔离技术细节，Application 层只依赖 Domain 的 Port 接口
- 简单 CRUD 直接委托，复杂查询可在此做结果转换

### 5.2 下游服务实现

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class {ServiceName}ClientImpl implements {ServiceName}Client {
    private final RestTemplate restTemplate;
    @Value("${app.downstream.{service}.base-url}")
    private String baseUrl;

    @Override
    public boolean sendNotification({EventName}Event event) {
        try {
            // 构建请求体、调用 RestTemplate
            return true;
        } catch (Exception ex) {
            log.error("Failed to call {service}, key: {}", event.key(), ex);
            return false;
        }
    }
}
```

**下游调用规则：**
- 端口在 `domain/downstream/`，实现在 `infrastructure/downstream/`
- For downstream error handling, timeout configuration, and WireMock testing conventions, see `.claude/rules/downstream-conventions.md`
- 考虑使用 Domain Event + `@TransactionalEventListener` 解耦，避免在事务内做 HTTP 调用

### 5.3 Config

```java
@Configuration
public class {Feature}Config {
    // 推荐 RestClient（Spring Framework 7，需 spring-boot-starter-restclient），RestTemplate 也可用于已有项目
    @Bean
    public RestTemplate downstreamRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }

    // 新项目推荐
    @Bean
    public RestClient downstreamRestClient(RestClient.Builder builder,
                                           @Value("${app.downstream.{service}.base-url}") String baseUrl) {
        return builder
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
```

***

## 6. Interfaces 层

Interfaces 层只做 HTTP 协议转换：HTTP Request → Java 调用 → HTTP Response。**零业务逻辑。**

### 6.1 Controller

```java
@RestController
@RequestMapping("/api/v1/{resources}")
@RequiredArgsConstructor
public class {Entity}Controller {
    private final {Entity}Service service;  // 注入接口，不注入实现

    @PostMapping
    public ResponseEntity<ApiResponse<{Entity}Response>> create(
            @Valid @RequestBody Create{Entity}Request request) {
        {Entity}Response response = service.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/{resources}/" + response.id()))
                .body(ApiResponse.success(response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<{Entity}Response>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.findById(id)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<{Entity}Response>>> list(
            {Entity}Query query, Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(service.list(query, pageable)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<{Entity}Response>> update(
            @PathVariable Long id,
            @Valid @RequestBody Update{Entity}Request request) {
        return ResponseEntity.ok(ApiResponse.success(service.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

**Controller 规则：**
- 所有端点返回 `ResponseEntity<ApiResponse<T>>`（DELETE 除外：返回 204 No Content，无响应体，见 api-conventions.md「DELETE Convention」）
- 用 `@Valid` 触发 Bean Validation，不做手动校验
- 不做任何业务判断（if/else、业务异常抛出、数据转换）
- URL 模式：`/api/v{version}/{resource-plural}`，RESTful 风格

### 6.2 统一响应体

```java
// SB4 / Jackson 3：jackson-annotations 包名不变，@JsonInclude 等注解仍可直接使用
@Getter @Builder @JsonInclude(NON_NULL)
public class ApiResponse<T> {
    private String code;
    private T data;
    private String message;
    private OffsetDateTime timestamp;
    private String traceId;
    private List<FieldError> errors;

    public static <T> ApiResponse<T> success(T data) { ... }
    public static ApiResponse<Void> error(ErrorCode code, String msg, String traceId) { ... }
    public static ApiResponse<Void> error(ErrorCode code, String msg, String traceId, List<FieldError> errors) { ... }
}
```

**Jackson 3 注意（SB4）：**
- `@JsonInclude` 等 jackson-annotations 包名不变，可直接使用
- 自定义序列化器注解 `@JsonComponent` → `@JacksonComponent`、`@JsonMixin` → `@JacksonMixin`
- 替换默认 JSON 处理器应定义 `JsonMapper` bean（不再是 `ObjectMapper`）
- 配置项 `spring.jackson.read.*` / `spring.jackson.write.*` → `spring.jackson.json.read.*` / `spring.jackson.json.write.*`

### 6.3 全局异常处理

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    // 处理 BusinessException, ValidationException, ConstraintViolation,
    // NoSuchElementException, DataIntegrityViolation, 兜底 Exception
    // 所有响应统一为 ResponseEntity<ApiResponse<Void>>
    // 包含 traceId + 敏感字段脱敏
}
```

**异常处理规则：**
- **禁止硬编码实体特定的 ErrorCode** — `DataIntegrityViolationException` 的处理应使用通用 `VALIDATION_ERROR`，而非 `USER_ALREADY_EXISTS`
- 所有异常转为 `ApiResponse<Void>` + traceId
- 敏感字段（password, token, secret）在 FieldError 中脱敏为 `***`

***

## 7. 跨领域关注点

### 7.1 审计（时间戳）

标准做法：`@PrePersist` / `@PreUpdate` 生命周期回调（见 §3.1 Entity 示例），不依赖 `@Builder.Default`、无需额外配置：

```java
@PrePersist
protected void onCreate() {
    createdAt = OffsetDateTime.now();
}

@PreUpdate
protected void onUpdate() {
    updatedAt = OffsetDateTime.now();
}
```

> 仅当需要记录「谁创建/谁修改」（auditor）时才引入 Spring Data JPA Auditing（`@CreatedDate` / `@LastModifiedDate` + `@CreatedBy` / `@LastModifiedBy` + `@EnableJpaAuditing`）；纯时间戳场景不必引入。

### 7.2 乐观锁（必须）

所有可变实体必须添加 `@Version`：

```java
@Version
private Long version;
```

JPA 自动处理：更新时检查 version，不匹配抛出 `OptimisticLockingFailureException`。

### 7.3 Domain Event（推荐）

用事件解耦副作用，避免在 Service 中直接调用下游：

```java
// Domain 层定义事件
public record {Entity}CreatedEvent(Long id, String name) {}

// Application 层发布事件
@Override
@Transactional
public {Entity}Response create(Create{Entity}Request request) {
    {Entity} saved = repository.save(mapper.toEntity(request));
    eventPublisher.publishEvent(new {Entity}CreatedEvent(saved.getId(), saved.getName()));
    return mapper.toResponse(saved);
}

// Infrastructure 层监听，事务提交后执行
@Slf4j
@Component
@RequiredArgsConstructor
public class {Entity}EventSubscriber {
    private final {ServiceName}Client client;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated({Entity}CreatedEvent event) {
        client.sendNotification(event);
    }
}
```

**好处：**
- Service 不依赖下游 Client，符合依赖规则
- 下游调用在事务外执行，不阻塞事务
- 新增副作用只需新增 Listener，符合开闭原则

### 7.4 软删除（按需）

```java
// Entity 添加
@Column(name = "deleted_at")
private OffsetDateTime deletedAt;

// Repository 查询自动过滤（Hibernate 7 使用 @SQLRestriction 替代已废弃的 @Where）
@SQLRestriction("deleted_at IS NULL")
@Entity
public class {Entity} { ... }

// 删除改为标记
public void softDelete() {
    this.deletedAt = OffsetDateTime.now();
}
```

### 7.5 分页安全

Controller 应限制 Pageable 的最大页面大小 —— 通过全局配置实现（`@PageableDefault` 没有 `maxPageSize` 属性）：

```yaml
# application.yml
spring:
  data:
    web:
      pageable:
        max-page-size: 100
        default-page-size: 20
```

```java
@GetMapping
public ResponseEntity<ApiResponse<Page<{Entity}Response>>> list(
        @PageableDefault(size = 20) Pageable pageable) {
    ...
}
```

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

### 9.1 测试分层

```
┌─────────────────────────────────────────────────────────────────┐
│  API Test (Integration)                                          │
│  {Entity}ApiTests extends BaseApiTest                            │
│  ─────────────────────────────────────────────────────────────── │
│  Spring Boot RANDOM_PORT + WebTestClient (real HTTP)             │
│  + H2 (in-memory DB) + WireMock (downstream mock)               │
│  + Apache SSHD (SFTP mock, opt-in)                               │
├─────────────────────────────────────────────────────────────────┤
│  Unit Test                                                       │
│  {ClassUnderTest}Test                                            │
│  ─────────────────────────────────────────────────────────────── │
│  JUnit 5 + Mockito (no Spring Context)                           │
└─────────────────────────────────────────────────────────────────┘
```

**包映射**：Integration → `integration/`、Contract → `contract/`、Unit → `unit/`（`{Entity}JpaAdapterTest` 为 @DataJpaTest 切片，归 `support/`）。Contract Test 使用 Spring Cloud Contract + MockMvc + RestAssuredMockMvc 验证 API 契约（URL、HTTP 方法、请求/响应结构），与 Integration 测试分属不同包。

### 9.2 服务器拓扑

```
WebTestClient ──HTTP──▶ Spring Boot App (RANDOM_PORT)
                          ├── Controller → Service → JpaAdapter → H2 (in-memory DB)
                          ├── Service → RestTemplate ──HTTP──▶ WireMock (downstream mock)
                          └── Service → SFTP Client ──SSH──▶ Apache SSHD (SFTP mock)
```

- **H2**: 替代 MySQL，通过 Flyway 建表 + `@Sql` 加载数据
- **WireMock**: 替代真实下游服务，MockFactory 注册 Stub，MockVerifier 验证调用
- **Apache SSHD**: 替代真实 SFTP 服务器，`@EnableSftpMock` 注解按需启用

### 9.3 测试数据流转

三组独立的数据源目录，分别服务于不同目的：

```
sql-data/                    test-data/                   mock-data/
├── cleanup/                 ├── {entity}/                ├── request/
│   └── clean-up.sql        │   ├── post/                │   └── {svc}-{action}.json
├── init/                    │   │   ├── request.json     ├── response/
│   └── data.sql             │   │   └── created.json     │   └── {svc}-{scenario}.json
└── cases/                   │   ├── get-by-id/           ├── mappings/
    └── {case}.sql           │   │   ├── ok.json          └── __files/
                             │   │   └── not-found.json
                             │   └── ...
                             └── ...
       │                          │                              │
       ▼                          ▼                              ▼
  @Sql annotation             FixtureFileLoader              MockFileLoader
  (BaseApiTest)               (test method)                  (MockFactory)
       │                          │                              │
       ▼                          ▼                              ▼
  H2 Database                 JSON assertion                 WireMock Stub
  (seed data)                 (req body + resp verify)       (req match + resp return)
```

| 数据源 | 加载方式 | 用途 |
|--------|---------|------|
| `sql-data/` | `@Sql` 注解（`BaseApiTest` 类级别 + 方法级别） | 数据库初始化：Cleanup → Schema → Init Data → Case Data |
| `test-data/` | `FixtureFileLoader.load(path, vars)` | 测试断言：构建请求体、验证响应体（支持 `${variable}` 模板） |
| `mock-data/` | `MockFileLoader.load(path, vars)` | WireMock Mock：请求体匹配模板、响应体返回模板（支持 `${json-unit.ignore}` 和 `${variable}`） |

### 9.4 测试基础设施

| 组件 | 位置 | 职责 |
|------|------|------|
| `BaseApiTest` | `integration/support/` | 抽象基类：`@SpringBootTest(RANDOM_PORT)` + `WebTestClient` + `@Sql` 数据重建 + HTTP 辅助方法 |
| `TestResourcePaths` | `support/utils/` | 路径解析中心：`test-data/`、`sql-data/`、`mock-data/` 三个基础路径 |
| `FixtureFileLoader` | `integration/support/fixture/` | 加载 `test-data/` 下的 JSON + `${variable}` 替换，用于构建请求和断言响应 |
| `MockFileLoader` | `support/mocks/` | 加载 `mock-data/` 下的 JSON + `${variable}` 替换，供 MockFactory 使用 |
| `JsonAssert` | `support/json/` | json-unit 断言：`assertJsonEquals`（lenient）+ `assertJsonEqualsStrict`（strict） |
| `DatabaseVerifier` | `support/sql/` | JDBC 直验：`count{Entities}`、`{entity}Exists`、`find{Entity}IdBy{Field}` 等 |
| `{Service}MockFactory` | `support/mocks/` | WireMock Stub 创建：使用 MockFileLoader + `equalToJson` 请求匹配 |
| `{Service}MockVerifier` | `support/mocks/` | WireMock 调用验证：验证下游被调用次数和参数 |
| `@EnableSftpMock` | `support/sftp/` | 内嵌 SFTP 服务器（Apache SSHD），按需启用 |
| `SftpMockSupport` | `support/sftp/` | SFTP 文件验证：`verifyFileUploaded`、`verifyFileExists` 等 |

> 详细的 API Test 编码规范、命名约定、模板和 Checklist 见 `.claude/rules/integration-test-guide.md`。

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