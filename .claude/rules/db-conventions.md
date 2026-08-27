---
paths:
  - "**/*.sql"
  - "**/infrastructure/**/*.java"
  - "**/*.yml"
  - "**/*.yaml"
  - "**/*.properties"
---
# Database Conventions

> **职责边界：** 本文件定义 JPA 实体（`{Entity}JpaEntity`）映射规则、`@Version` 乐观锁、持久化映射、索引策略、N+1 查询防护。迁移文件规范见 `db-migration.md`，分层位置见 `architecture.md` §5.1。

***

## Persistence 分层（六边形架构）

持久化全部技术细节收敛在 `infrastructure/adapter/out/persistence/`，**领域模型（`domain/model/`）不含任何 JPA 注解**：

```
infrastructure/adapter/out/persistence/
├── entity/{Entity}JpaEntity.java        # JPA 实体（技术对象，非领域模型）
├── repository/{Entity}JpaRepository.java  # Spring Data JPA 接口
├── mapper/{Entity}PersistenceMapper.java  # JpaEntity ↔ 领域模型双向转换（唯一转换处）
├── adapter/{Entity}RepositoryAdapter.java # 实现 application/port/out 仓库端口
└── config/JpaConfig.java                # 配置就近管理
```

***

## Migration Rules

- All DDL via Flyway migration
- Naming: `V{version}__{description}.sql`
- **Flyway 10+ 方言拆分**：MySQL 支持需额外依赖 `org.flywaydb:flyway-mysql`（SB4 的 flyway starter 只带 flyway-core，缺失时启动报 `Unsupported Database: MySQL 8.0`）
- **Never modify merged migrations** — add corrective migrations instead
- Test data via `@Sql` scripts in `src/test/resources/sql-data/` (cleanup + init + cases)
- 集成/e2e 测试用 Testcontainers 起真实 MySQL，Flyway 自动执行迁移，不再维护 H2 方言分支；MySQL 专有语法无需拆分 `db/migration/mysql/`

> 完整迁移规范见 `db-migration.md`。

***

## JpaEntity Rules

> 基于 **JPA / Hibernate 7**（Spring Boot 默认持久层）。**MyBatis Plus** 项目的对应方案见下方「MyBatis Plus 替代」小节。

### JpaEntity 模板

```java
// infrastructure/adapter/out/persistence/entity/{Entity}JpaEntity.java
@Entity
@Table(name = "{table_name}")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class {Entity}JpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 必须字段：nullable = false + 业务约束
    @Column(nullable = false, unique = true, length = 50)
    private String name;

    // 枚举：必须 STRING 持久化，禁止 ORDINAL
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private {Entity}Status status;

    // 乐观锁：所有可变实体必须
    @Version
    private Long version;

    // 时间戳：OffsetDateTime，不用 Date/LocalDateTime
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
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
}
```

> JpaEntity 是纯持久化对象：**不放业务方法**（业务行为在 `domain/model/{Entity}.java`）、不用 `@Builder` 强制工厂（由 PersistenceMapper 装配字段）。审计时间戳、乐观锁、软删除都是它的职责。

### PersistenceMapper 模板

```java
// infrastructure/adapter/out/persistence/mapper/{Entity}PersistenceMapper.java
public final class {Entity}PersistenceMapper {

    private {Entity}PersistenceMapper() {}

    public static {Entity} toDomain({Entity}JpaEntity entity) {
        // 领域工厂/构造器完成不变式重建
        return new {Entity}(new {Entity}Id(entity.getId()), entity.getName(), …);
    }

    public static {Entity}JpaEntity toEntity({Entity} entity) {
        {Entity}JpaEntity result = new {Entity}JpaEntity();
        result.setId(entity.getId() != null ? entity.getId().value() : null);
        result.setName(entity.getName());
        return result;
    }
}
```

**规则：** 双向转换只在此处发生；字段变更时只改这一个文件 + 领域模型。

### 关键规则

| 规则 | 说明 |
|------|------|
| 位置 | `{Entity}JpaEntity` 在 `infrastructure/adapter/out/persistence/entity/`，禁止出现在 `domain/` |
| Id | auto-generated (`IDENTITY` for MySQL)；聚合根也可用业务标识（`String` + 值对象映射） |
| Timestamps | `@PrePersist` / `@PreUpdate` 生命周期回调（见 `architecture.md` §7.1），`OffsetDateTime` 类型；不依赖 `@Builder.Default`，纯时间戳场景不引入 JPA Auditing |
| Enums | use `@Enumerated(EnumType.STRING)`, **never ORDINAL** |
| Columns | never nullable for required fields, use `nullable = false` |
| **乐观锁** | **所有可变 JpaEntity 必须启用 `@Version`**（防止并发更新丢失数据） |
| 构造器保护 | `@NoArgsConstructor(access = PROTECTED)`，字段由 PersistenceMapper 装配 |
| 软删除 | `deletedAt` 字段 + `@SQLRestriction("deleted_at IS NULL")`（Hibernate 7，替代已废弃的 `@Where`） |

### 约束三层对齐（DTO ↔ 实体 ↔ DDL）

应用层校验都可能被绕过（直接 SQL、迁移脚本），数据库 `NOT NULL`/唯一/外键约束是不依赖调用路径的最终保证。**DTO 注解、实体注解、DDL 三层必须表达同一约束集：**

| 代码层约束 | DDL 对应 | 不一致的典型后果 |
|-----------|---------|----------------|
| `@NotNull` / `@NotBlank` | `NOT NULL` | 校验放行的 null 在 INSERT 时报 500 |
| `@Size(max = 64)` | `VARCHAR(64)` | 校验放行 64 字符，列仅 32，截断或报错 |
| 业务唯一字段 | `UNIQUE` 约束 | 代码未查重，并发下重复数据落盘 |

**规则：**
- 对齐检查是**迁移审查固定环节**——Flyway 变更合入时 diff 列约束与实体/DTO 注解（对应 NC-013，见 `null-check-governance.md` §3）
- 反向不一致同样有害：DDL 更严会把入口 400 推迟为持久层 500，**修复方向是补 DTO 注解而非放松 DDL**
- 兜底是「兜」而非「堵」：`DataIntegrityViolationException` → 409 由 `exception-handling.md` §6.2 统一映射，友好报错由前置校验给出

### @Version (Optimistic Locking)

```sql
-- DDL
CREATE TABLE {table} (
    ...
    version BIGINT DEFAULT 0 NOT NULL,
    ...
);
```

```java
// JpaEntity
@Version
private Long version;
```

JPA 自动处理：更新时检查 version，不匹配抛 `OptimisticLockingFailureException`。**由 `{Entity}RepositoryAdapter` 翻译为领域异常**（如 `{Entity}VersionConflictException`，带 cause），不把 Spring Data 异常类型泄露到应用层（`architecture.md` §7.2）。

### MyBatis Plus 替代(JPA → MyBatis Plus)

选用 MyBatis Plus 的工程,持久化对象（`{Entity}MpEntity`，同样位于 `infrastructure/adapter/out/persistence/entity/`）对应方案:

| 关注点 | JPA(Hibernate 7) | MyBatis Plus |
|--------|------|------|
| 乐观锁 | `jakarta.persistence.@Version` | `com.baomidou.mybatisplus.annotation.@Version` + 注册 `OptimisticLockerInnerInterceptor` |
| 审计时间戳 | `@CreatedDate` / `@LastModifiedDate` + `AuditingEntityListener` | `@TableField(fill = INSERT / INSERT_UPDATE)` + 自定义 `MetaObjectHandler` |
| ID 策略 | `@GeneratedValue(IDENTITY / SEQUENCE)` | `@TableId(IdType.AUTO / ASSIGN_ID)` |
| 分页 | Spring Data `Pageable` | `IPage` + `MybatisPlusInterceptor` + `PaginationInnerInterceptor` |
| 枚举持久化 | `@Enumerated(EnumType.STRING)` | `@EnumValue` 标记码值,或配置 `default-enum-type-handler` |
| 表/列映射 | `@Entity` / `@Table` / `@Column` | `@TableName` / `@TableField` |

***

## 测试数据库策略

**集成/e2e 测试使用 Testcontainers 真实 MySQL**（与生产同方言、同 Flyway 迁移），不依赖 H2 模拟——消除方言差异导致的假阳性/假阴性：

```java
@Container
static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

@DynamicPropertySource
static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", mysql::getJdbcUrl);
    registry.add("spring.datasource.username", mysql::getUsername);
    registry.add("spring.datasource.password", mysql::getPassword);
}
```

> H2 仅在无法使用容器的受限 CI 环境作为降级选项；此时核心迁移须保持 H2 兼容（见下表）。

### H2 兼容参考（仅降级场景）

| MySQL | H2 | Notes |
|-------|----|-------|
| AUTO_INCREMENT | GENERATED BY DEFAULT AS IDENTITY | ID generation |
| ENGINE=InnoDB | omit | Storage engine |
| CHARSET=utf8mb4 | omit | Character set |
| TIMESTAMP | TIMESTAMP WITH TIME ZONE | MySQL TIMESTAMP converts to UTC, H2 preserves offset |
| INT | INTEGER or BIGINT | Integer types |
| BOOLEAN / TINYINT(1) | BOOLEAN | Boolean mapping |
| TEXT | CLOB | Large text |
| JSON | VARCHAR(8192) or CLOB | H2 has no native JSON type |
| ON UPDATE CURRENT_TIMESTAMP | omit — use `@PreUpdate` instead | MySQL-specific auto-update |
| FULLTEXT INDEX | omit — use application-level search | H2 does not support FULLTEXT |

**OffsetDateTime 与 MySQL 的时区行为：** MySQL 的 `TIMESTAMP` 列存储时转换为 UTC，读取时转换为会话时区（不保留时区偏移）。`DATETIME` 不做转换。H2 的 `TIMESTAMP WITH TIME ZONE` 保留完整偏移量。

***

## Index Strategy

**何时创建索引：**

- 外键列（`CONSTRAINT fk_{table}_{referenced} FOREIGN KEY ...`）
- 常查询的 WHERE 条件列
- 唯一约束（`UNIQUE INDEX`）
- 排序/分页查询的 ORDER BY 列

**复合索引规则：**
- 高选择性列在前
- 遵循最左前缀匹配

**命名约定：**
- 普通索引：`idx_{table}_{column}`
- 唯一索引：`uk_{table}_{column}`
- 外键：`fk_{table}_{referenced_table}`

***

## N+1 Query Prevention

```java
// 方式一：@EntityGraph 预加载关联
@EntityGraph(attributePaths = {"orders"})
List<{Entity}JpaEntity> findAll();

// 方式二：JOIN FETCH in @Query
@Query("SELECT u FROM {Entity}JpaEntity u JOIN FETCH u.orders WHERE u.id = :id")
Optional<{Entity}JpaEntity> findWithOrders(@Param("id") Long id);

// 方式三：@BatchSize 批量加载
@BatchSize(size = 50)
@OneToMany(mappedBy = "user")
private List<OrderJpaEntity> orders;
```

> 计数场景禁止 `findByXxx(…).size()`——出端口提供 `countByXxx`（`architecture.md` §8 反模式 #10）。

***

## Spring Boot 4 / Hibernate 7 数据层变化

- **Hibernate 7**：`@SQLRestriction` 替代已废弃的 `@Where`（软删除过滤），行为不变
- **`@EntityScan` 迁包**：import 改为 `org.springframework.boot.persistence.autoconfigure.EntityScan`
- **Hibernate 注解处理器**：`hibernate-jpamodelgen` → `hibernate-processor`（生成 JPA 静态元模型）
- **异常翻译开关**：`spring.dao.exceptiontranslation.enabled` → `spring.persistence.exceptiontranslation.enabled`
- **Flyway 需专用 starter**：`spring-boot-starter-flyway`（不再仅靠第三方依赖）
