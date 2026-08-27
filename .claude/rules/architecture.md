---
paths:
  - "**/domain/**/*.java"
  - "**/application/**/*.java"
  - "**/infrastructure/**/*.java"
---
# 六边形架构规范（Ports & Adapters）

基于六边形架构（Hexagonal / Ports & Adapters）的包结构最佳实践。适用于 Spring Boot 4.x + JPA（Hibernate 7）项目。

> **架构权威：** 完整设计指南（含代码示例与说理）见 `docs/SpringBoot六边形架构包结构设计指南.md`。本文件定义**分层规则、包结构、各层职责概述、跨领域关注点、反模式**；各层的详细编码规范见对应专题文件（见文末导航表）。

***

## 1. 分层与依赖规则

三层结构：**领域层（domain）为核心，应用层（application）包裹领域层，基础设施层（infrastructure）在最外层**。

```
                      ┌─────────────────────────────────────┐
                      │  Infrastructure                     │
                      │  adapter.in: web/messaging/scheduler│
                      │  adapter.out: persistence/…/external│
                      └──────────────┬──────────────────────┘
                                     │ 依赖
                      ┌──────────────▼──────────────────────┐
                      │  Application                        │
                      │  port.in (UseCase), port.out,       │
                      │  service, dto                       │
                      └──────────────┬──────────────────────┘
                                     │ 依赖
                      ┌──────────────▼──────────────────────┐
                      │  Domain                             │
                      │  model, event, exception            │
                      │  （零框架依赖）                       │
                      └─────────────────────────────────────┘
```

**依赖规则（必须严格遵守）：**

| 层 | 允许依赖 | 禁止依赖 |
|----|---------|---------|
| Domain | 仅 JDK（测试可用 JUnit/AssertJ） | Spring、JPA、Kafka、Lombok 等一切框架 |
| Application | domain；Spring 装配注解（`spring-context`/`spring-tx`）；Lombok | infrastructure、任何 starter、JPA/Kafka/HTTP 客户端等技术 |
| Infrastructure | application、domain、Spring Boot 全家桶 | —（最外层） |

**核心原则：**

- 依赖方向永远向内指向领域层；外层可以依赖内层，内层绝对不可依赖外层。
- `adapter.in` 只调用 `application.port.in`（UseCase 接口），不直接触碰领域对象与出端口。
- `adapter.out` 实现 `application.port.out` 接口，技术细节（JPA、Kafka、RestClient）止步于此。
- **Repository / Gateway / EventPublisher 端口只在 `application/port/out` 定义一份**，domain 与 infrastructure 均不重复定义。

***

## 2. 包结构

```
com.example.{project}
├── {Project}Application.java
├── domain/                                  # 领域层（零框架依赖）
│   ├── model/                               # 实体、值对象（纯 POJO / record）
│   │   ├── {Entity}.java
│   │   ├── {Entity}Id.java                  # 标识符值对象（可选）
│   │   └── …Vo.java                         # 带校验/计算规则的值对象
│   ├── service/                             # 领域策略/领域服务（按需，零框架依赖）
│   │   └── {BusinessConcept}Policy.java     # 跨聚合事实的规则计算，不访问端口
│   ├── event/
│   │   ├── DomainEvent.java                 # 事件标记接口
│   │   └── {Entity}CreatedEvent.java        # record，implements DomainEvent
│   └── exception/                           # 类型化业务异常
│       ├── DomainException.java             # 公共基类（errorCode + message）
│       └── {BusinessCondition}Exception.java
├── application/
│   ├── port/
│   │   ├── in/                              # 入端口（Driving Port）
│   │   │   └── {Action}{Entity}UseCase.java
│   │   └── out/                             # 出端口（Driven Port）——仓库接口唯一定义处
│   │       ├── {Entity}Repository.java
│   │       ├── {Service}Gateway.java        # 外部系统端口
│   │       └── EventPublisher.java
│   ├── service/
│   │   └── {Entity}Service.java             # implements XxxUseCase
│   └── dto/
│       ├── {Action}{Entity}Command.java
│       └── {Entity}Dto.java                 # 含 from() 静态工厂
├── infrastructure/
│   ├── adapter/
│   │   ├── in/                              # 入站适配器（Upstream）
│   │   │   ├── web/
│   │   │   │   ├── controller/{Entity}Controller.java
│   │   │   │   ├── dto/                     # Request / Response
│   │   │   │   ├── common/ApiResponse.java
│   │   │   │   ├── mapper/{Entity}WebMapper.java
│   │   │   │   └── exception/GlobalExceptionHandler.java
│   │   │   ├── messaging/                   # 消息消费者
│   │   │   └── scheduler/                   # 定时任务
│   │   └── out/                             # 出站适配器（Downstream）
│   │       ├── persistence/
│   │       │   ├── entity/{Entity}JpaEntity.java
│   │       │   ├── repository/{Entity}JpaRepository.java
│   │       │   ├── adapter/{Entity}RepositoryAdapter.java
│   │       │   ├── mapper/{Entity}PersistenceMapper.java
│   │       │   └── config/JpaConfig.java    # 配置就近管理
│   │       ├── messaging/                   # 消息生产者（KafkaEventPublisher）
│   │       └── external/                    # 外部系统（{Service}GatewayAdapter + config）
│   └── config/                              # 仅跨适配器共享的全局配置（SecurityConfig 等）
└── （测试见 §9，位于 src/test/java 同构包）
```

**命名约定：**

| 类型 | 命名模式 | 示例 |
|------|---------|------|
| 入端口 | `{Action}{Entity}UseCase` | `CreateOrderUseCase` |
| 应用服务 | `{Entity}Service` | `OrderService` |
| 出端口（仓库） | `{Entity}Repository` | `UserRepository` |
| 出端口（外部系统） | `{Service}Gateway` | `PaymentGateway` |
| JPA 实体 | `{Entity}JpaEntity` | `UserJpaEntity` |
| Spring Data 接口 | `{Entity}JpaRepository` | `UserJpaRepository` |
| 仓库适配器 | `{Entity}RepositoryAdapter` | `UserRepositoryAdapter` |
| 持久化映射 | `{Entity}PersistenceMapper` | `UserPersistenceMapper` |
| Web 映射 | `{Entity}WebMapper` | `OrderWebMapper` |
| 应用层 DTO | `{Action}{Entity}Command` / `{Entity}Dto` | `CreateOrderCommand` / `UserDto` |
| Web 层 DTO | `{Action}{Entity}Request` / `{Entity}Response` | `CreateOrderRequest` / `UserResponse` |
| 领域异常 | `{BusinessCondition}Exception` | `InsufficientStockException` |
| 领域事件 | `{Entity}{Action}Event` | `OrderCreatedEvent` |
| Controller | `{Entity}Controller` | `UserController` |

***

## 3. Domain 层（架构核心）

领域层只包含纯 Java 代码，**零框架依赖**——不引入 Spring、JPA、Kafka、Lombok。可在纯 JVM 环境毫秒级运行单元测试。

### 3.1 实体（model）

- 具有唯一标识的业务对象，用**领域方法**（`changeEmail()` / `activate()` 等意图明确的方法）封装状态变更，而非贫血数据袋 + setter
- 构造与工厂中完成不变式校验，非法状态无法被创建
- 不含 JPA 注解——持久化映射是 infrastructure 的事（见 §5.1）

### 3.2 值对象（model）

- 非持久化 VO 首选 `record`，compact constructor 中做格式/业务校验
- **纯计算逻辑内聚到值对象**（如 `PricingPolicy.calculateFinalPrice()`）；无自然宿主的规则计算放领域策略（`domain/service/`，见 §3.5）
- 判断标准：同一段校验/计算出现在两处以上，就应提取为 VO

> VO 完整示例与 OO 设计约束见 `java-object-calisthenics.md`。

### 3.3 领域事件（event）

- `DomainEvent` 标记接口 + `record` 事件（`implements DomainEvent`），为 `EventPublisher` 端口提供类型安全
- 事件携带业务事实（`OrderCreatedEvent`），不携带技术细节

### 3.4 类型化业务异常（exception）

- 每个业务条件一个异常类，携带**稳定错误码常量**与业务上下文（实体 id、冲突值）
- 可选公共基类 `DomainException`（`errorCode` + message）减少样板
- **禁止异常类爆炸之外的两个极端**：禁止裸 `RuntimeException`，也不引入全项目单一 `BusinessException` + 错误码枚举单体

> 异常体系完整定义、抛出/捕获规范、全局处理见 `exception-handling.md`。

### 3.5 应用服务与领域服务的边界

业务规则按「自然宿主」归属，编排与规则分离：

| 归属 | 判断标准 | 位置 |
|------|---------|------|
| 实体方法 / 值对象 | 规则的自然宿主（操作自身状态/自身数据） | `domain/model/` |
| 领域策略/领域服务（按需） | 纯规则计算，但不自然归属单一实体/VO（输入是跨聚合事实，如按历史订单数定阶梯折扣） | `domain/service/` |
| 应用服务 | 编排：查事实、调端口、事务边界、发事件 | `application/service/` |

**领域策略规则（`domain/service/`）：**

- 纯 Java、零框架依赖（无 Spring 注解），与领域层其余部分同一铁律
- 需要的事实由应用层查好后作为参数传入，**不注入任何端口、不做 I/O**——出现 repository/HTTP 调用即违规（那是编排，归应用层）
- 无状态；由应用层直接实例化，或经配置类 `@Bean` 工厂方法注册为 Bean
- 两种反面对照：规则写成应用 Service 私有方法（规则泄露、领域贫血）；`domain/service` 里放带端口注入的「服务」（破坏零依赖）

**应用服务规则：** 只做编排，不包含具体业务规则；跨实体编排放 `application/service`，同层 Service 可直接协作。

***

## 4. Application 层

应用层负责用例编排：接收 Command/Query → 调用领域对象 → 调用出端口 → 返回 DTO。**不包含业务规则**，业务规则在 Domain 层。

> Service 写法、事务管理、DTO 映射、乐观锁处理、方法命名等完整规范见 `service-conventions.md`。

**核心要点：**

- 入端口 `{Action}{Entity}UseCase` 接口声明系统提供的能力；应用服务 `implements` 对应 UseCase
- 应用服务是 Spring Bean：`@Service` + `@RequiredArgsConstructor` + `@Transactional(readOnly = true)` 类级别（写操作覆盖为 `@Transactional`）
- 只注入 `application/port/out` 接口，禁止注入任何适配器实现类
- DTO 全部使用 `record`；请求 Command 带 Bean Validation 注解；DTO ↔ 领域对象转换用 DTO 静态工厂（`from()`）
- 禁止在事务方法内做耗时的下游/外部调用——通过 `EventPublisher` 发布事件，由出站适配器在 `afterCommit` 后外发（见 §7.3）

***

## 5. Infrastructure 层

Infrastructure 层实现 `application/port/out` 定义的端口，所有技术细节（JPA、Kafka、HTTP、配置）封装在此层。

### 5.1 持久化（两类文件 + 两类映射）

```java
// 1. JPA 实体（技术对象，非领域模型）
@Entity @Table(name = "…")                    // infrastructure/adapter/out/persistence/entity/
public class {Entity}JpaEntity { … }          // @Version、@PrePersist 等落在这里

// 2. Spring Data 接口
public interface {Entity}JpaRepository extends JpaRepository<{Entity}JpaEntity, Long> { … }

// 3. 适配器 — 实现 application.port.out 的仓库端口
@Component
public class {Entity}RepositoryAdapter implements {Entity}Repository { … }

// 4. 持久化映射 — JpaEntity ↔ 领域模型双向转换，集中一处
public final class {Entity}PersistenceMapper { toDomain() / toEntity() }
```

**为什么分离：** 领域模型保持零框架依赖；JPA 注解、乐观锁、审计时间戳都是 JpaEntity 的技术细节，经 PersistenceMapper 隔离。

> Entity 模板、`@Version`、H2 兼容、索引策略见 `db-conventions.md`。

### 5.2 消息与外部系统

- `KafkaEventPublisher implements EventPublisher`——**必须注册 `TransactionSynchronization.afterCommit`**，只在事务提交成功后外发
- `{Service}GatewayAdapter implements {Service}Gateway`——RestClient 调用外部 HTTP 服务

> 下游 HTTP 客户端实现、错误分类、弹性模式见 `downstream-conventions.md`。

### 5.3 配置就近原则

适配器私有配置（`JpaConfig`、`KafkaConfig`、`PaymentConfig`）放在对应适配器目录的 `config/` 下就近管理；顶层 `infrastructure/config/` 仅保留跨适配器共享的全局配置（如 `SecurityConfig`）。

### 5.4 入站适配器

- `adapter.in/web` 只做 HTTP 协议转换：Request → UseCase 调用 → `ResponseEntity<ApiResponse<T>>`，**零业务逻辑**
- Web 层请求/响应 DTO 在 `adapter/in/web/dto/`，与 `application/dto` 严格分开；经 `{Entity}WebMapper` 转换
- 全局异常处理 `GlobalExceptionHandler` 在 `adapter/in/web/exception/`，Controller 不写 try-catch

> URL 模式、HTTP 方法语义、状态码映射、分页约定见 `api-conventions.md`。

***

## 6. 测试架构

> 测试分层、包结构、命名约定见 `test-conventions.md`，契约测试见 `contract-test.md`，TDD 工作流见 `tdd-workflow.md`。

```
┌────────────────────────────────────────────────────────────┐
│  e2e — {Entity}FlowTest                                  │
│  @SpringBootTest + @AutoConfigureMockMvc + WireMock        │
├────────────────────────────────────────────────────────────┤
│  integration — {Adapter}Test                               │
│  @SpringBootTest / @DataJpaTest + Testcontainers (MySQL)   │
├────────────────────────────────────────────────────────────┤
│  unit — {ClassUnderTest}Test                               │
│  domain: 纯 JUnit | application: Mockito (no Spring ctx)   │
└────────────────────────────────────────────────────────────┘
```

***

## 7. 跨领域关注点

### 7.1 审计（时间戳）

`@PrePersist` / `@PreUpdate` 生命周期回调写在 **JpaEntity**（infrastructure 层），领域模型不感知：

```java
@PrePersist
protected void onCreate() { createdAt = OffsetDateTime.now(); }

@PreUpdate
protected void onUpdate() { updatedAt = OffsetDateTime.now(); }
```

> 仅当需要记录「谁创建/谁修改」时才引入 Spring Data JPA Auditing；纯时间戳场景不必引入。

### 7.2 乐观锁（必须）

所有可变 JpaEntity 必须添加 `@Version`。并发冲突（`OptimisticLockingFailureException`）由**适配器翻译**为领域异常（如 `OrderVersionConflictException`），不把 Spring Data 异常类型泄露到应用层。

> Service 层乐观锁异常处理见 `service-conventions.md` §5。

### 7.3 领域事件与事务一致性（推荐）

用事件解耦副作用。**禁止在事务内外发外部消息**——事务回滚后消息已发出会造成不一致：

```java
// Application 层发布事件（事务内，仅注册意图）
eventPublisher.publish(new {Entity}CreatedEvent(saved.getId(), …));

// Infrastructure 出站适配器：注册 afterCommit，事务提交成功后才真正外发
if (TransactionSynchronizationManager.isActualTransactionActive()) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { doSend(event); }
    });
} else {
    doSend(event);
}
```

**好处：** Service 不依赖下游适配器；外发在事务外执行，不阻塞事务；新增副作用只需新增出站适配器/监听器（开闭原则）。

### 7.4 软删除（按需）

软删除是持久化细节，落在 JpaEntity：`deletedAt` 字段 + `@SQLRestriction("deleted_at IS NULL")`（Hibernate 7，替代已废弃的 `@Where`）。

### 7.5 分页安全

Controller 层通过全局配置限制 Pageable 最大页面大小（`@PageableDefault` 没有 `maxPageSize` 属性）：

```yaml
spring:
  data:
    web:
      pageable:
        max-page-size: 100
        default-page-size: 20
```

> 分页 API 规范详见 `api-conventions.md`。

***

## 8. 反模式（禁止）

| # | 反模式 | 为什么有问题 | 正确做法 |
|---|-------|------------|---------|
| 1 | **领域层引入框架依赖**（Spring/JPA/Lombok 注解出现在 domain） | 破坏零依赖铁律，测试被迫加载容器 | 领域层纯 Java；JPA 注解放 JpaEntity |
| 2 | **贫血实体**：只有 getter/setter 无行为 | 业务逻辑散落应用层，实体退化为数据结构 | 用领域方法封装状态变更 |
| 3 | **domain 与 application 双份仓库接口** | 两份签名几乎一致的接口，维护隐患 | 仓库接口只在 `application/port/out` 定义一份 |
| 4 | **adapter.in 直接操作领域对象/出端口** | 绕过 UseCase 编排，破坏边界 | Controller 只依赖 `port.in` 接口 |
| 5 | **Service 注入适配器实现类**（`UserRepositoryAdapter`） | 绕过端口抽象，无法替换技术实现 | 注入 `port.out` 接口 |
| 6 | **应用层与 Web 层 DTO 混用** | 传输格式变化波及应用层，Command 沾染 HTTP 语义 | `application/dto` 与 `adapter/in/web/dto` 分开，WebMapper 转换 |
| 7 | **映射逻辑散落各处** | 转换规则不可追溯，字段变更易漏改 | 跨技术栈转换集中在适配器 `mapper/`；应用层用 DTO 静态工厂 |
| 8 | **事务内外发消息** | 事务回滚但消息已发出，数据/消息不一致 | 事件适配器注册 `afterCommit` 后发送 |
| 9 | **配置类集中堆放** | 无关配置互相牵连，适配器不可插拔 | 适配器私有配置就近放置，顶层只留共享配置 |
| 10 | **为拿计数加载全量列表**（`findByUserId(x).size()`） | 全量实体进内存，性能反模式 | 出端口提供 `countByUserId` |
| 11 | **业务规则塞进应用服务 / 领域层放入带 I/O 的「服务」** | 规则泄露致领域贫血；或破坏 domain 零依赖铁律 | 规则优先实体/VO；无自然宿主的规则放 `domain/service` 纯策略（事实由应用层传入）；编排归 `application/service` |
| 12 | **枚举用 ORDINAL 持久化** | 数据库值无意义，枚举重排序导致数据错乱 | JpaEntity 必须 `@Enumerated(EnumType.STRING)` |
| 13 | **skip `@Version`** | 并发更新丢失数据 | 所有可变 JpaEntity 必须 `@Version` |
| 14 | **字段注入 `@Autowired`** | 隐藏依赖、难以测试 | 构造器注入 `@RequiredArgsConstructor` |
| 15 | **裸 `Optional.get()` / 返回 null 表示不存在** | 无业务语义，NPE 风险 | 出端口返回 `Optional<T>`；应用层 `orElseThrow` 领域异常 |

> 判空专题的坏味道编号（NC-001~NC-014，可工具扫描）与改造执行流程见 `null-check-governance.md`。

***

## 9. 测试包结构（src/test/java 同构）

```
src/test/java/{base-package}/
├── unit/                         # 纯逻辑测试，零容器
│   ├── domain/                   # 纯 JUnit + AssertJ
│   └── application/              # Mockito mock 出端口
├── integration/                  # 适配器集成测试
│   └── {Entity}RepositoryAdapterTest.java   # Testcontainers MySQL
├── e2e/                          # 端到端测试
│   └── {Entity}FlowTest.java   # MockMvc + WireMock
├── contract/                     # 契约测试（API 层增强）
└── support/                      # 共享测试工具（mocks、json、sql）
```

> 完整规范见 `test-conventions.md`。

***

## 10. 新增业务模块 Checklist

以 `{Entity} = Order` 为例：

**Phase 1 — Domain（先建领域模型与契约）**
- [ ] `domain/model/Order.java` — 实体 + 领域方法 + 不变式
- [ ] `domain/model/OrderId.java` 等值对象（按需）
- [ ] `domain/event/OrderCreatedEvent.java`（按需）
- [ ] `domain/exception/OrderNotFoundException.java` 等类型化异常

**Phase 2 — Application（定义端口与用例）**
- [ ] `application/port/in/CreateOrderUseCase.java` 等入端口
- [ ] `application/port/out/OrderRepository.java`（仓库接口唯一定义处）
- [ ] `application/dto/CreateOrderCommand.java` + `OrderDto.java`
- [ ] `application/service/OrderService.java`

**Phase 3 — Infrastructure（实现适配器）**
- [ ] `infrastructure/adapter/out/persistence/entity/OrderJpaEntity.java`
- [ ] `infrastructure/adapter/out/persistence/repository/OrderJpaRepository.java`
- [ ] `infrastructure/adapter/out/persistence/mapper/OrderPersistenceMapper.java`
- [ ] `infrastructure/adapter/out/persistence/adapter/OrderRepositoryAdapter.java`
- [ ] `db/migration/V{N}__create_orders_table.sql` — Flyway

**Phase 4 — Web（暴露 HTTP）**
- [ ] `infrastructure/adapter/in/web/dto/CreateOrderRequest.java` + `OrderResponse.java`
- [ ] `infrastructure/adapter/in/web/mapper/OrderWebMapper.java`
- [ ] `infrastructure/adapter/in/web/controller/OrderController.java`

**Phase 5 — Test**
- [ ] `unit/domain/` + `unit/application/OrderServiceTest.java` — 单元测试
- [ ] `integration/OrderRepositoryAdapterTest.java` — Testcontainers
- [ ] `e2e/OrderFlowTest.java` — MockMvc + WireMock + JSON fixtures + @Sql
- [ ] `contract/` — Spring Cloud Contract（按需）

***

## 11. 多模块演进（进阶）

小型项目以单模块结构起步；当模块间出现编译瓶颈、或需要用编译期约束强制领域层零依赖时，演进为 Maven 多模块：`domain`（零框架依赖）→ `application`（仅注解依赖）→ `infrastructure`（Spring Boot）→ `bootstrap`（聚合启动）。

> 多模块完整结构、POM 模板与包扫描配置见设计指南第十章。

***

## 专题文件导航

| 主题 | 文件 |
|------|------|
| 技术栈与版本 | `tech-stack.md` |
| Service 层完整规范 | `service-conventions.md` |
| API 设计规范 | `api-conventions.md` |
| 异常处理完整规范 | `exception-handling.md` |
| 参数校验规范 | `validation.md` |
| 判空治理（NC 规则与改造执行） | `null-check-governance.md` |
| Java 编码规范 | `java-coding-standard.md` |
| SOLID 与迪米特法则 | `java-solid-lod.md` |
| 对象健身操 | `java-object-calisthenics.md` |
| 日志规范 | `logging.md` |
| 数据库规范 | `db-conventions.md` |
| 数据库迁移 | `db-migration.md` |
| 下游集成规范 | `downstream-conventions.md` |
| 测试规范总则 | `test-conventions.md` |
| 契约测试 | `contract-test.md` |
| TDD 工作流 | `tdd-workflow.md` |
| Code Review | `code-review.md` |
