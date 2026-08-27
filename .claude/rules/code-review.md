---
paths:
  - "**/*.java"
---
# Code Review Checklist

**版本：** 1.3（2026-08-22 同步主线规范：纯赋值构造器 checklist、`@PathVariable` 格式约束细化（字符串 ID `@Pattern` / 数值 ID `@Positive`）；§2.1 模式选型指针已先行合入）

> **职责边界：** 本文件是 Code Review 的**统一入口**，汇总各专题文件的审查要点，不重复具体规则定义——每条指向对应规范文件的具体章节。

***

## Checklist

### 架构与分层（→ `architecture.md`）
- [ ] 六边形三层（Domain → Application → Infrastructure）依赖向内是否维护？
- [ ] Domain 层是否零框架依赖（Spring/JPA/Kafka/Lombok 均无）？
- [ ] Repository 端口是否只在 application/port/out 定义一份、adapter.out 实现？
- [ ] 下游 Gateway 是否 application/port/out 接口、adapter/out/external 实现？
- [ ] Service 是否只注入 port/out 接口（而非适配器实现类/JpaRepository）？

### Java 编码规范（→ `java-coding-standard.md`）
- [ ] 所有 `public` / `protected` 类和方法是否有 JavaDoc？
- [ ] 构造器注入（`@RequiredArgsConstructor`），无字段 `@Autowired`？
- [ ] DTO 是否用 record？
- [ ] 命名是否清晰、无自造缩写？
- [ ] 大括号是否始终使用？`@Override` 是否标注？
- [ ] 工具选择是否遵循优先级（JDK/Spring → Lombok → Commons）？
- [ ] Lombok `@Data` 是否避开 JPA Entity？
- [ ] 纯赋值构造器是否一律用 `@RequiredArgsConstructor`（非 Spring Bean 同适用，见 java-coding-standard.md §5.2）？
- [ ] 异常链是否保留 cause？

### 对象健身操（→ `java-object-calisthenics.md`）
- [ ] 业务代码是否避免 `else` 关键字（强制）？
- [ ] 是否无自造缩写（强制）？
- [ ] 方法缩进是否 ≤ 2 层（推荐）？
- [ ] 领域 Entity 是否通过领域方法操作状态，而非 public setter？

### SOLID 与迪米特法则（→ `java-solid-lod.md`）
- [ ] Service/Domain 是否只依赖 Domain Port 接口（DIP 强制），无 `new` 具体实现、无直接注入 JpaRepository/RestClient？
- [ ] 新增策略/副作用是否走扩展点（策略 Bean / Domain Event），而非修改既有 if-else 链（OCP）？
- [ ] 接口实现是否满足契约：不强化前置条件、不弱化后置条件、不抛未声明异常（LSP）？
- [ ] 是否无胖接口强迫空实现（ISP）、无跨层穿透调用如 Controller→Repository（LoD）？
- [ ] 「取数据 → 判断 → 改数据」的逻辑是否放在数据所在的类里（LoD / Feature Envy）？

### API 设计（→ `api-conventions.md`）
- [ ] Service 默认具体 class；仅多实现/策略时抽接口（勿为单实现强抽接口）？
- [ ] Controller 是否无业务逻辑？
- [ ] 所有端点返回 `ResponseEntity<ApiResponse<T>>`（DELETE 除外：204 No Content 无响应体）？
- [ ] URL 模式是否遵循 `/api/v{version}/{resource-plural}` RESTful 风格？
- [ ] `@PathVariable` ID 是否标注格式约束（字符串 ID 用 `@Pattern`，数值 ID 用 `@Positive`）？

### Service 层（→ `service-conventions.md`）
- [ ] `@Transactional(readOnly = true)` 是否在类级别？
- [ ] 写操作是否用 `@Transactional` 覆盖？
- [ ] 事务方法内是否无下游 HTTP 调用？
- [ ] Mapper 是否 `@Component` + 手动映射（不用 MapStruct）？

### 参数校验（→ `validation.md`）
- [ ] DTO 字段是否用 Bean Validation 注解（而非手动 `Assert`）？
- [ ] Controller 是否用 `@Valid` / `@Validated` 触发验证？
- [ ] 嵌套对象是否加了 `@Valid` 级联验证？
- [ ] `@ConfigurationProperties` 类是否加了 `@Validated`？
- [ ] `Optional` 是否仅用于方法返回值（未用作字段/参数）？（→ `java-coding-standard.md` §3.3）
- [ ] Null 安全是否采用三层防御（JSpecify `@NullMarked` + `Optional` + `Assert`）？（→ `java-coding-standard.md` §4.2）
- [ ] String 字段是否用 `@NotBlank` 而非 `@NotNull`、嵌套配置是否加 `@Valid`？（NC-006/NC-014 → `null-check-governance.md` §3）

### 异常处理（→ `exception-handling.md`，业务异常表达模式选型见其 §2.1）
- [ ] 业务错误是否全部通过类型化领域异常（`domain/exception/` + `CODE` 常量）表达？
- [ ] 新增业务错误是否新增了独立异常类（而非只加错误码枚举值或裸 `RuntimeException`）？
- [ ] Controller 是否零 try-catch？
- [ ] 兜底 500 是否固定文案、不回显 `ex.getMessage()`？
- [ ] `AccessDeniedException` 是否有显式 handler？
- [ ] 错误响应中敏感字段是否脱敏？

### 数据库（→ `db-conventions.md` / `db-migration.md`）
- [ ] DB 变更是否有 Flyway migration？
- [ ] 所有可变实体是否有 `@Version`？
- [ ] 枚举持久化是否用 `@Enumerated(EnumType.STRING)`？
- [ ] 时间戳是否用 `@PrePersist` / `@PreUpdate` + `OffsetDateTime`？
- [ ] 已合并的 migration 是否未被修改？

### 下游集成（→ `downstream-conventions.md`）
- [ ] 下游 Gateway 接口是否在 application/port/out（如调用外部服务）？
- [ ] API 测试中是否有 WireMock MockFactory/Verifier？

### 日志（→ `logging.md`）
- [ ] 日志是否用 `@Slf4j`（Lombok），无手动 `Logger` 字段？
- [ ] 占位符格式（`{}`）是否正确，异常对象为最后参数？
- [ ] 密码、token、PII 是否脱敏后才记录？

### 测试（→ `test-conventions.md` / `integration-test-guide.md` / `contract-test.md`）
- [ ] 测试命名：`*FlowTest` for e2e, `*Test` for unit, `*ContractTest` for contract？
- [ ] Contract Test 是否覆盖新端点？
- [ ] 单元测试是否无 Spring Context（纯 Mockito）？
- [ ] 测试数据是否确定性（无 `UUID.randomUUID()` / `System.currentTimeMillis()`）？
- [ ] `@Sql` 脚本是否幂等？

***

## Architecture Review
- [ ] Hexagonal three-layer separation maintained (Domain -> Application -> Infrastructure), dependencies pointing inward
- [ ] Domain layer has no Spring/framework dependencies
- [ ] Repository port defined only in application/port/out, implemented by adapter.out
- [ ] Downstream Gateway is interface in application/port/out, implemented in adapter/out/external

***

## Refactoring Guide

### Pre-conditions
- [ ] All tests pass
- [ ] Code coverage > 80%

### Steps
1. Identify code smells (duplication, long methods, unclear naming)
2. Write characterization tests if coverage is low
3. Apply refactoring incrementally
4. Run tests after each change
5. Update documentation if public API changed

### Safety Rules
- Never refactor without passing tests
- One refactoring at a time
- Prefer small commits
