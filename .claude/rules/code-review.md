---
paths:
  - "**/*.java"
---
# Code Review Checklist

> **职责边界：** 本文件是 Code Review 的**统一入口**，汇总各专题文件的审查要点，不重复具体规则定义——每条指向对应规范文件的具体章节。

***

## Checklist

### 架构与分层（→ `architecture.md`）
- [ ] 四层分离（Domain → Application → Infrastructure → Interfaces）是否维护？
- [ ] Domain 层是否无 Spring/framework 依赖？
- [ ] Repository 是否 domain 层接口、infrastructure 层实现？
- [ ] 下游 Client 是否 domain 层接口、infrastructure 层实现？
- [ ] Service 是否直接注入 Domain Repository 接口（而非 JpaRepository）？

### Java 编码规范（→ `java-coding-standard.md`）
- [ ] 所有 `public` / `protected` 类和方法是否有 JavaDoc？
- [ ] 构造器注入（`@RequiredArgsConstructor`），无字段 `@Autowired`？
- [ ] DTO 是否用 record？
- [ ] 命名是否清晰、无自造缩写？
- [ ] 大括号是否始终使用？`@Override` 是否标注？
- [ ] 工具选择是否遵循优先级（JDK/Spring → Lombok → Commons）？
- [ ] Lombok `@Data` 是否避开 JPA Entity？
- [ ] 异常链是否保留 cause？

### 对象健身操（→ `java-object-calisthenics.md`）
- [ ] 业务代码是否避免 `else` 关键字（强制）？
- [ ] 是否无自造缩写（强制）？
- [ ] 方法缩进是否 ≤ 2 层（推荐）？
- [ ] 领域 Entity 是否通过领域方法操作状态，而非 public setter？

### API 设计（→ `api-conventions.md`）
- [ ] Service 默认具体 class；仅多实现/策略时抽接口（勿为单实现强抽接口）？
- [ ] Controller 是否无业务逻辑？
- [ ] 所有端点返回 `ResponseEntity<ApiResponse<T>>`（DELETE 除外：204 No Content 无响应体）？
- [ ] URL 模式是否遵循 `/api/v{version}/{resource-plural}` RESTful 风格？
- [ ] `@PathVariable` ID 是否标注 `@Positive`？

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

### 异常处理（→ `exception-handling.md`）
- [ ] 业务错误是否全部通过 `BusinessException` + `ErrorCode` 表达？
- [ ] 新增错误是否只新增 `ErrorCode` 枚举值（而非新异常类）？
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
- [ ] 下游 Client 接口是否在 domain 层（如调用外部服务）？
- [ ] API 测试中是否有 WireMock MockFactory/Verifier？

### 日志（→ `logging.md`）
- [ ] 日志是否用 `@Slf4j`（Lombok），无手动 `Logger` 字段？
- [ ] 占位符格式（`{}`）是否正确，异常对象为最后参数？
- [ ] 密码、token、PII 是否脱敏后才记录？

### 测试（→ `test-conventions.md` / `integration-test-guide.md` / `contract-test.md`）
- [ ] 测试命名：`*ApiTests` for API tests, `*ContractTest` for contract？
- [ ] Contract Test 是否覆盖新端点？
- [ ] 单元测试是否无 Spring Context（纯 Mockito）？
- [ ] 测试数据是否确定性（无 `UUID.randomUUID()` / `System.currentTimeMillis()`）？
- [ ] `@Sql` 脚本是否幂等？

***

## Architecture Review
- [ ] Four-layer separation maintained (Domain -> Application -> Infrastructure -> Interfaces)
- [ ] Domain layer has no Spring/framework dependencies
- [ ] Repository is interface in domain, implementation in infrastructure
- [ ] Downstream client is interface in domain, implementation in infrastructure

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
