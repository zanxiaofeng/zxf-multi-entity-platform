# multi-entity-platform

单代码库多实体部署平台骨架 —— 对《[单代码库多实体部署：架构设计与 SpringBoot4 落地实践](docs/单代码库多实体部署-架构设计与SpringBoot4落地实践.md)》第五章落地骨架的完整实现。

技术基线：**Spring Boot 4.1（Spring Framework 7）· JDK 21（虚拟线程）· Maven 多模块**。
核心思路：共享内核只做抽象，差异代码进独立 SPI 模块，运行时通过统一实体配置 + `EntityContext` 装配与路由。

## 模块结构（文档 5.1）

```text
multi-entity-platform/
├── platform-core/          # 共享内核：抽象、上下文、横切能力（不允许分叉）
├── entity-alpha/           # 实体 Alpha 的 SPI 实现（独立 module）
├── entity-beta/            # 实体 Beta 的 SPI 实现
├── app/                    # 启动模块，构建时按实体裁剪打包
├── deploy/                 # K8s 部署清单示例（三开关成对，文档 6.3）
└── .github/workflows/      # 装配矩阵 CI（文档 5.7）
```

依赖方向严格单向：`entity-* → platform-core`，`app → platform-core`。
`app` 通过 Maven profile 决定打包哪个实体模块——实体 B 的类不进入实体 A 的镜像。
`-Palpha` 与 `-Pbeta` 产出**两个不同的二进制产物**，CI 分别构建与冒烟，互不背书（文档 2.4 / 5.4）。

## 快速开始

```bash
# 装配矩阵验证（两实体分别全量构建 + 测试）。
# 本地开发注意：IDE 增量编译会污染 target/classes，直接 verify 可能产生假失败——
# 始终带 clean 且在根 reactor 跑（CI 全新检出无此问题）
mvn clean verify -Palpha
mvn clean verify -Pbeta

# 运行 Alpha 实体（产物名带实体标识）
SPRING_PROFILES_ACTIVE=alpha java -jar app/target/app-1.0.0-SNAPSHOT-alpha.jar

# 运行 Beta 实体（另一终端）
SPRING_PROFILES_ACTIVE=beta SERVER_PORT=8081 java -jar app/target/app-1.0.0-SNAPSHOT-beta.jar
```

验证计价差异（同一契约，不同 SPI 实现）：

```bash
curl -X POST localhost:8080/api/v1/orders -H 'Content-Type: application/json' \
     -d '{"item":"widget","quantity":2}'
# Alpha → {"price":{"amount":226,"currency":"CNY"}}   （200 × 1.13 增值税）
# Beta  → {"price":{"amount":190,"currency":"CNY"}}   （200 × 0.95 折）
# 金额经 Money 构造期 scale 归一化，对外恒为最简十进制形态（write-bigdecimal-as-plain，无科学计数法）

curl localhost:8080/actuator/info    # → {"entity":"ALPHA"}，运行期漂移巡检（文档 6.3）
```

> 注意：`mvn verify`（不带 profile）会在 app 模块被 Enforcer 拒绝——不打实体 profile 的产物是空壳，启动必然失败，构建期直接拦截。

## 与文档章节的映射

| 文档章节 | 落点 |
| --- | --- |
| 5.1.1 六边形分包 | core = `interfaces.{filter,rest}` / `application{.command,.assembler,.port}` / `domain.{model,service,event,port}` / `infrastructure.{persistence,engine,observation}`（`onionArchitecture` ArchUnit 守护）；实体模块 = `adapter` 子包 |
| 5.2.1 实体标识与上下文 | `core.context.EntityType` / `EntityContext`（含 JSpecify `@Nullable`） |
| 5.2.2 边缘识别一次路由 | `interfaces.filter.EntityContextFilter`：上下文 + MDC 同一 try/finally 生命周期；`TraceIdFilter`（最前序）：`X-Trace-Id` 白名单校验 + MDC/响应头注入（logging.md） |
| 5.2.3 上下文传播 | `AsyncConfig.entityContextPropagator`（TaskDecorator：EntityContext + 全量 MDC 快照，traceId 随车）+ 显式 `applicationTaskExecutor`（虚拟线程 + `@Primary`） |
| 5.2.4 / 5.2.5 扩展点与注册表 | `domain.port.PricingPolicy` / `application.port.PolicyRegistry`（构造器启动期 fail-fast + `priceFor` 解析期 fail-loud；`hasPolicy` 包私有仅供装配冒烟） |
| 5.3 SPI 模块 | `entity-alpha` / `entity-beta`，`@ForEntity` 限定（5.10.1），专属迁移脚本与 profile 配置（`application-{entity}.yaml`）随模块打包 |
| 5.4 装配 | `app/pom.xml` Maven profile 裁剪 + 产物名带实体标识 + 无实体感知启动类 |
| 5.5 通用逻辑 | `application.OrderApplicationService`：零实体判断（ArchUnit 守护） |
| 5.7 装配冒烟矩阵 | 实体模块轻量装配测试 + app `@SpringBootTest`（按 `assembly.entity` 门控）+ 漂移负例 + GitHub Actions 矩阵 |
| 5.8.1 管道编排 | `OrderPipeline` + `OrderStep`（domain.port 纯契约）：core 公共首步 `SchemaValidationStep`，实体第二步 `RiskCheckStep`（alpha）/ `AuditExtraStep`（beta），`@Order` 排序、装配冒烟逐字比对步骤名 |
| 5.8.2 模板方法 | `domain.service.AbstractDocumentGenerator`（骨架 `final`）+ Alpha/Beta 页眉页脚实现（差异点 ≤ 2 的适用边界见类 Javadoc） |
| 5.8.3 Schema 驱动校验 | `PlatformValidationProperties`（规则在 `application-{entity}.yaml`）+ `SchemaDrivenValidator`，违反 → 400（`RestExceptionHandler`），未知规则字段 → 配置缺陷显式失败 |
| 5.9 对象健身操 | `OrderId`/`Money` 值对象（`MoneyConverter` autoApply 落 `price` 单列，V4 收敛 + V5 NOT NULL 补偿）；`policies.priceFor(order)` 单点调用；`PlatformProperties` record 构造器绑定 |
| 6.1 Flyway 迁移 | `db/migration/common`（core）+ `db/migration/alpha\|beta`（实体模块），locations 按 `platform.entity` 组合 |
| 6.3 配置一致性三防线 | 启动期 `PolicyRegistry` 校验 → CI 装配矩阵 → `/actuator/info` 实体巡检 |
| 7.0 Flowable 版本硬性约束 | `flowable.version=8.0.0` + Enforcer 锚定 `org.flowable:*:*:[7.0.0,8.0.0)` 禁用 |
| 7.1 流程拓扑差异外置 | `infrastructure.engine.OrderApprovalService`（实现 `domain.port.OrderApprovalPort`）：只按契约 key（`order-approval`）发起实例；变量只放轻量标识（`OrderId` 值对象） |
| 7.2 部署级隔离 | BPMN 在实体模块 `processes/` 随 profile 裁剪：Alpha 风控+三级审批 / Beta 五级审批+审计留痕；专属 delegate `@ForEntity` 限定（5.10.1），通用 delegate 进 core |
| 7.3② ACT_* 表治理 | `common/V3__flowable_engine_tables.sql`（提取自官方 jar 的 H2 DDL）+ `flowable.database-schema-update=false` |
| 7.3③ 引擎线程可观测性 | `FlowableJobContextConfig`：`SpringAsyncExecutor` 挂 `EntityContextPropagatingTaskExecutor` 传播 MDC/entity；流程变量显式携带 `entity` 双保险——delegate 基类 `EntityContextAwareDelegate` 在 Job 线程从变量重建上下文（闭环，e2e 覆盖 async 通知任务） |
| 7.4 流程装配冒烟 | `AlphaProcessAssemblySmokeTest` / `BetaProcessAssemblySmokeTest`：同 key 定义唯一、delegate 全装配、拓扑符合预期 |
| 8.2 Maven Enforcer | core 禁依赖 `entity-*`；实体模块互禁依赖；app 强制要求 `assembly.entity`；Flowable 版本锚定 |
| 8.3 ArchUnit | core 洋葱分层（`onionArchitecture`，适配器间互禁依赖）；白名单外不得感知 `EntityType`/静态上下文；领域核心（model/service/event）零实体感知；领域对象禁 setter；core 不做 BPMN 解析；扩展点/管道步骤实现必须 `@ForEntity` 元注解限定（`beMetaAnnotatedWith(Conditional.class)`，5.10.1）；实体互禁依赖；delegate 实例字段必须 final |

## Spring Boot 4 适配点（文档 5.0 的实际落地）

- **JDK 21**：虚拟线程经 `spring.threads.virtual.enabled=true` 开启（Enforcer 强制 JDK ≥ 21）。
- **模块化拆包**：Flyway 自动配置在 `spring-boot-flyway` 模块，需显式引入；`@AutoConfigureMockMvc` 移至 `org.springframework.boot.webmvc.test.autoconfigure`（`spring-boot-webmvc-test` 模块）。
- **Jackson 3**：DTO/命令对象用 record，Jackson 3 原生序列化，无需自定义适配。
- **`@ConfigurationProperties`**：record 构造器绑定 + `@Validated` 声明式校验（`PlatformProperties` / `PlatformValidationProperties`，validation.md §2.8；compact constructor 仅保留默认值处理）。
- **JSpecify**：`EntityContext.currentOrNull()`、`AuditEntry` 标注 `@Nullable`。

## 配置一致性（三个开关成对，文档 6.3）

| 开关 | Alpha | Beta |
| --- | --- | --- |
| 构建产物 | `mvn package -Palpha` | `mvn package -Pbeta` |
| `SPRING_PROFILES_ACTIVE` | `alpha,prod` | `beta,prod` |
| `platform.entity` | `alpha` | `beta` |

任一漂移 → 启动期 `PolicyRegistry` 直接失败（负例测试覆盖）。

> `@ForEntity`（5.10.1 已落地）直接读 `platform.entity` 决定激活；`SPRING_PROFILES_ACTIVE` 通过激活 `application-{entity}.yaml` 间接提供 `platform.entity`——三者仍成对，激活机制已统一为单一开关源（不再裸 `@Profile`）。

## Review 硬性规则（文档 8.1，已由工具强制的部分）

1. `platform-core` 白名单外的包出现 `EntityType`/`EntityContext` 引用 → ArchUnit 测试红（白名单：`context`、`application.port`、`interfaces.filter`、`infrastructure.*`、`domain.port`——`supports()` 声明适配实体属端口契约）。
2. 扩展点实现未加 `@ForEntity`（未被 `@Conditional` 元注解限定）→ ArchUnit 测试红。
3. 实体模块相互依赖 / core 依赖实体模块 / Flowable 版本 < 8.0 → Enforcer 构建失败。
4. 异步路径未传播上下文 → `EntityContextPropagatorTest` / `EntityContextPropagatingTaskExecutorTest` + 端到端审计断言守护。
5. BPMN 引用未装配的 delegate、同 key 双定义 → 流程装配冒烟测试红（引擎不做启动期校验，必须自研）。
6. delegate 实例字段非 final（存执行态风险）→ ArchUnit 测试红；delegate 未继承 `EntityContextAwareDelegate`（绕过 Job 线程上下文重建）→ ArchUnit 测试红。
7. `EntityContext` 仅限同步 Servlet 栈；引入 WebFlux 需架构评审。
8. 事务内副作用（审计等）必须走领域事件 + `@TransactionalEventListener(AFTER_COMMIT)`（文档 8.1 规则 11）。
9. 领域对象出现 setter → ArchUnit 测试红；应用层链式调用超两点、标识/金额未用值对象 → review 打回（文档 8.1 规则 12）。

## Flowable 运维纪律（文档 7.3 / 8.1.9，需团队知晓的持续成本）

- `flowable.database-schema-update=false`：ACT_* 表归 Flyway 管（`common/V3`，H2 方言，提取自官方 jar；换 Oracle 需重新提取）。
- **每次升级 Flowable 版本 = 人工核对 ACT_* 差异并补齐 Flyway 脚本**（官方只发布 Liquibase changelog）。核对范围含四类 schema：common / process / **idm（ACT_ID_\*，在独立的 flowable-idm-engine jar，易漏）** / eventregistry——引擎启动逐一校验，缺 IDM 表会报误导性的 `db version is 5.99.0.0`。
- 流程定义变更走 expand-and-contract：删除/重命名节点前用公共 API（`runtimeService.createProcessInstanceQuery()`）核查在途实例；在途迁移用 `createProcessInstanceMigrationBuilder()`。
- 告警：活跃流程实例数 + **deadletter job 数**（非零即人工介入），按 entity 维度分别配置。

## 演进方向（文档第九章）

实体数增长到 >3 时，按文档路径平滑迁移：注册表键枚举 → `String`；SPI 收敛为独立
`platform-spi` 模块语义化版本管理；实体模块独立仓库，运行期 ServiceLoader 发现。
