---
paths:
  - "**/application/**/*.java"
---
# Service Layer Conventions

Service 层负责用例编排：接收 Command/Query → 调用领域对象 → 调用出端口 → 返回 DTO。**不包含业务规则**（业务规则在 Domain 层）。

> **职责边界：** 本文件是 Service 层的**唯一权威**——Service 写法、事务管理、DTO 映射、乐观锁处理、方法命名。分层规则见 `architecture.md` §4。

***

## 1. Service（implements UseCase，注入出端口）

每个用例一个入端口接口（`application/port/in`），应用服务实现之；依赖一律是 `application/port/out` 接口，绝不注入适配器实现类。

```java
// application/port/in/CreateOrderUseCase.java
public interface CreateOrderUseCase {
    OrderDto createOrder(CreateOrderCommand command);
}

// application/service/OrderService.java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService implements CreateOrderUseCase {
    private final OrderRepository orderRepository;       // port/out
    private final UserRepository userRepository;         // port/out
    private final EventPublisher eventPublisher;         // port/out，按需注入
    private final PricingService pricingService;         // 同层协作（跨实体编排）

    @Override
    @Transactional                                     // 写操作覆盖为读写
    public OrderDto createOrder(CreateOrderCommand command) { … }

    // 查询方法继承类级别 readOnly = true，无需额外注解
}
```

**规则：**

- **方法签名以 UseCase 接口为准**：一个 Service 可实现多个相关 UseCase（同一实体的增删改查），Controller 依赖接口而非实现
- Service 之间可直接协作（如 `OrderService` 注入 `PricingService`），这是应用层内部的跨实体编排，无需再抽端口
- **跨实体编排逻辑放应用层 Service**；纯计算逻辑内聚到领域值对象；单个聚合内部行为在实体方法（`architecture.md` §3.5）

***

## 2. 事务管理

### 类级别 vs 方法级别

```java
@Service
@Transactional(readOnly = true)          // 类级别：默认只读
public class {Entity}Service {

    @Transactional                       // 写操作覆盖为读写
    public {Entity}Dto create(Create{Entity}Command command) { … }

    // 查询方法继承类级别 readOnly = true，无需额外注解
    public {Entity}Dto findById(String id) { … }
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

## 3. 禁止事务内外部调用

**规则：禁止在 `@Transactional` 方法内直接调用外部系统**（下游 HTTP、消息外发、文件存储等）。

原因：外部调用耗时不确定，持有数据库连接和事务锁期间做 I/O 严重影响性能；且事务回滚后外部副作用无法撤销。

**解决方案：EventPublisher 端口 + afterCommit**

```java
// Service 层发布事件（事务内，仅注册意图）
@Override
@Transactional
public {Entity}Dto create(Create{Entity}Command command) {
    {Entity} saved = orderRepository.save(…);
    eventPublisher.publish(new {Entity}CreatedEvent(saved.getId(), saved.getName()));
    return {Entity}Dto.from(saved);
}

// Infrastructure 出站适配器：事务提交成功后才真正外发
// （KafkaEventPublisher 内部注册 TransactionSynchronization.afterCommit，
//   或经本地事件监听 @TransactionalEventListener(AFTER_COMMIT) 转发，见 architecture.md §7.3）
```

好处：
- Service 不依赖外部适配器，符合依赖规则
- 外部调用在事务外执行，不阻塞事务，回滚不产生幽灵消息
- 新增副作用只需新增出站适配器，符合开闭原则

> 下游集成完整规范见 `downstream-conventions.md`。

***

## 4. DTO 约定

### 应用层 DTO（Command / Query / Dto）

```java
// 创建命令：所有必填字段带 Validation
public record Create{Entity}Command(
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 50, message = "Name must be 2-50 characters")
    String name,
    @NotNull(message = "Type is required")
    {Entity}Type type
) {}

// 更新命令：字段可选（null 表示不更新）
public record Update{Entity}Command(
    Long id,
    @Size(min = 2, max = 50, message = "Name must be 2-50 characters")
    String name,
    @Email(message = "Must be a valid email")
    String email
) {}

// 出参 DTO：无 Validation 注解；from() 静态工厂承载 领域对象 → DTO 转换
public record {Entity}Dto(
    String id,
    String name,
    {Entity}Status status,
    OffsetDateTime createdAt
) {
    public static {Entity}Dto from({Entity} entity) {
        return new {Entity}Dto(
            entity.getId().value(),
            entity.getName(),
            entity.getStatus(),
            entity.getCreatedAt()
        );
    }
}
```

**DTO 规则：**

- 全部使用 `record`
- Command/Query 带 Bean Validation 注解，出参 DTO 不带
- Create 的必填字段用 `@NotBlank` / `@NotNull`，Update 的字段可选（null = 不更新）
- **DTO ↔ 领域对象转换用 DTO 上的静态工厂（`from()` / `toDomain()`）承载**——应用层不设 mapper 目录；跨技术栈转换（JPA/Web）才集中在各适配器 `mapper/`（`architecture.md` §5.1）
- Web 层 Request/Response 在 `adapter/in/web/dto/`，经 `{Entity}WebMapper` 与应用层 DTO 互转，**禁止混用**（`architecture.md` §8 反模式 #6）

> Bean Validation 完整规范见 `validation.md`。

### 部分更新语义

Update Command 中字段为 `null` 表示**不更新**，而非清空：

```java
@Override
@Transactional
public {Entity}Dto update(Update{Entity}Command command) {
    {Entity} entity = repository.findById(new {Entity}Id(command.id()))
        .orElseThrow(() -> new {Entity}NotFoundException(command.id()));
    if (command.name() != null) { entity.rename(command.name()); }
    return {Entity}Dto.from(entity);
}
```

***

## 5. 乐观锁处理

并发冲突由持久化适配器把 `OptimisticLockingFailureException` 翻译为领域异常（`architecture.md` §7.2），Service 层按业务决策处理：

```java
// 处理方式一：返回冲突错误（默认）
@Override
@Transactional
public {Entity}Dto update(Update{Entity}Command command) {
    try {
        …
    } catch ({Entity}VersionConflictException ex) {
        throw ex;   // 或转换为面向调用方的领域异常
    }
}

// 处理方式二:重试(适用于低冲突场景)
// 注意:需引入 spring-retry 依赖 + 配置类加 @EnableRetry;且确保 Retry advice 顺序先于 Transaction advice
// (否则重试不会每次拿新事务)。未引入 spring-retry 时只用方式一(返回 409)
@Retryable({Entity}VersionConflictException.class, maxAttempts = 3)
@Transactional
public {Entity}Dto update(Update{Entity}Command command) { … }
```

> 异常处理完整规范见 `exception-handling.md`。

***

## 6. 方法命名标准化

Service 方法名即 UseCase 方法名，**不加 entity 后缀**（已限定上下文）：

| 操作 | 命名 | 示例 |
|------|------|------|
| 创建 | `create` | `create(CreateCommand)` |
| 按 ID 查询 | `findById` | `findById(String id)` |
| 列表查询 | `list` | `list(Query, Pageable)` |
| 更新 | `update` | `update(UpdateCommand)` |
| 删除 | `delete` | `delete(String id)` |
| 存在性检查 | `existsByName` | `existsByName(String name)` |
| 计数 | `countByXxx` | `countByUserId(String userId)` — 禁止 `findByXxx(…).size()` |

***

## 7. 出端口使用规则

- 出端口（`application/port/out`）是 Service 对外部能力的**唯一依赖入口**：`{Entity}Repository`、`{Service}Gateway`、`EventPublisher`
- 出端口方法签名用领域对象 / 值对象 / 原始类型，**禁止出现 JPA、Page 之外的 Spring Data 类型**（`Page`/`Pageable` 为允许的分页例外）
- 返回单个用 `Optional<T>`，禁止返回 null；返回集合用 `List<T>` 或 `Page<T>`
- Gateway 方法参数使用 Command/事件对象，禁止超过 3 个原始参数（`downstream-conventions.md`）
