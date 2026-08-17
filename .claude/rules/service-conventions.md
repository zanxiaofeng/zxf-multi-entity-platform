---
paths:
  - "**/application/**/*.java"
---
# Service Layer Conventions

Service 层负责业务编排：接收 DTO → 调用 Domain → 返回 DTO。**不包含业务规则**（业务规则在 Domain 层）。

> **职责边界：** 本文件是 Service 层的**唯一权威**——Service 写法、事务管理、DTO 映射、乐观锁处理、方法命名。

***

## 1. Service（具体 class，按需抽接口）

Application Service 默认用**具体 `@Service` class**（简洁，避免无意义的接口/Impl 拆分）。仅当存在多实现、策略模式、或需为不同调用方/下游模块提供稳定契约时，才抽取接口。

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class {Entity}Service {
    private final {Entity}Repository repository;
    private final {Entity}Mapper mapper;
    private final ApplicationEventPublisher eventPublisher; // 按需注入
```

> **何时抽接口：** 多实现（按 profile/配置选择不同实现）、策略模式、或为下游模块提供稳定契约。**单实现 Service 不要为"模式"强抽接口**——属过度设计（Mockito 可直接 mock 具体类；Domain Port 已解耦 infrastructure，application service 对 controller 无需再套一层接口）。

***

## 2. 事务管理

### 类级别 vs 方法级别

```java
@Service
@Transactional(readOnly = true)          // 类级别：默认只读
public class {Entity}Service {

    @Transactional                       // 写操作覆盖为读写
    public {Entity}Response create(Create{Entity}Request request) { ... }

    // 查询方法继承类级别 readOnly = true，无需额外注解
    public {Entity}Response findById(Long id) { ... }
}
```

### 传播行为

| 传播类型 | 使用场景 |
|----------|----------|
| `REQUIRED`（默认） | 绝大多数业务方法 |
| `REQUIRES_NEW` | 审计日志（无论外层事务成功与否都要记录） |
| `NESTED` | 基于 savepoint 的嵌套(**同一物理事务**,非独立子事务);内层异常仍向上传播,外层只有 `catch` 该异常才能保留更改(回滚到 savepoint)。MySQL/PostgreSQL JDBC 支持,**H2 与 JTA 不支持**。审计等真正需独立事务的场景用 `REQUIRES_NEW` |

### 回滚规则

```java
// 默认行为：仅对 RuntimeException 和 Error 回滚，不对 checked Exception 回滚
// 需要覆盖时：
@Transactional(rollbackFor = Exception.class)
```

### 自引用代理陷阱

同 bean 内部方法调用**绕过 AOP 代理**，`@Transactional` 不生效：

```java
// BAD: 内部调用绕过代理
public void methodA() {
    this.methodB(); // methodB 的 @Transactional 不生效
}

// 解决方式一：@Lazy 自注入
@Lazy
@Autowired
private {Entity}Service self;

public void methodA() {
    self.methodB(); // 通过代理调用
}

// 解决方式二：重构为独立 Service
```

### 只读事务优化

`@Transactional(readOnly = true)` 的性能收益：
- Hibernate 跳过脏检查（dirty checking）
- Flush 模式设为 MANUAL，避免不必要的 SQL 同步
- 部分数据库驱动优化查询（如 MySQL 只读连接）

***

## 3. 禁止事务内下游调用

**规则：禁止在 `@Transactional` 方法内直接调用下游 HTTP 服务。**

原因：下游调用耗时不确定，持有数据库连接和事务锁期间做 HTTP 调用会严重影响性能和一致性。

**解决方案：Domain Event + `@TransactionalEventListener`**

```java
// Service 层发布事件（事务内）
@Override
@Transactional
public {Entity}Response create(Create{Entity}Request request) {
    {Entity} saved = repository.save(mapper.toEntity(request));
    eventPublisher.publishEvent(new {Entity}CreatedEvent(saved.getId(), saved.getName()));
    return mapper.toResponse(saved);
}

// Infrastructure 层监听（事务提交后执行）
@Slf4j
@Component
@RequiredArgsConstructor
public class {Entity}EventSubscriber {
    private final {Service}Client client;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated({Entity}CreatedEvent event) {
        client.sendNotification(event);
    }
}
```

好处：
- Service 不依赖下游 Client，符合依赖规则
- 下游调用在事务外执行，不阻塞事务
- 新增副作用只需新增 Listener，符合开闭原则

> 下游集成完整规范见 `downstream-conventions.md`。

***

## 4. DTO 映射约定

### Mapper 模式

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

### DTO 规则

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

> Bean Validation 完整规范见 `validation.md`。

### 部分更新语义

Update DTO 中字段为 `null` 表示**不更新**，而非清空：

```java
// Service 实现部分更新
@Override
@Transactional
public {Entity}Response update(Long id, Update{Entity}Request request) {
    {Entity} entity = repository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.{ENTITY}_NOT_FOUND, id));
    if (request.name() != null) { entity.rename(request.name()); }
    return mapper.toResponse(entity);
}
```

***

## 5. 乐观锁处理

使用 `@Version` 的实体在并发更新时抛出 `OptimisticLockingFailureException`：

```java
// Service 层处理方式一：返回冲突错误
@Override
@Transactional
public {Entity}Response update(Long id, Update{Entity}Request request) {
    try {
        {Entity} entity = repository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.{ENTITY}_NOT_FOUND, id));
        if (request.name() != null) { entity.rename(request.name()); }
        return mapper.toResponse(entity);
    } catch (OptimisticLockingFailureException ex) {
        throw new BusinessException(ErrorCode.VERSION_CONFLICT, id);
    }
}

// Service 层处理方式二:重试(适用于低冲突场景)
// 注意:需引入 spring-retry 依赖 + 配置类加 @EnableRetry;且确保 Retry advice 顺序先于 Transaction advice
// (否则重试不会每次拿新事务)。未引入 spring-retry 时只用方式一(返回 409)
@Retryable(OptimisticLockingFailureException.class, maxAttempts = 3)
@Transactional
public {Entity}Response update(Long id, Update{Entity}Request request) { ... }
```

> 异常处理完整规范见 `exception-handling.md`。

***

## 6. 方法命名标准化

Service 已限定 entity 上下文，**方法名不加 entity 后缀**：

| 操作 | 命名 | 示例 |
|------|------|------|
| 创建 | `create` | `create(CreateRequest)` |
| 按 ID 查询 | `findById` | `findById(Long id)` |
| 列表查询 | `list` | `list(Query, Pageable)` |
| 更新 | `update` | `update(Long id, UpdateRequest)` |
| 删除 | `delete` | `delete(Long id)` |
| 存在性检查 | `existsByName` | `existsByName(String name)` |

***

## 7. 下游调用委托

下游调用通过 Domain Event 解耦。直接的下游客户端接口定义在 `domain/downstream/`，实现在 `infrastructure/downstream/`。

> 详细的下游集成规则见 `downstream-conventions.md`。
