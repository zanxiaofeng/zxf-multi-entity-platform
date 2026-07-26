# 仓库说明

Spring Boot 4（4.1.x）单代码库多实体部署骨架。设计文档：`docs/单代码库多实体部署-架构设计与SpringBoot4落地实践.md`，章节落点映射见 `README.md`。

## 构建

- JDK 21：`/home/davis/.jdks/ms-21.0.10`（系统 mvn 3.9.12 默认即 JDK 21）
- 装配矩阵（必须带实体 profile，否则 app 模块 Enforcer 拒绝构建）：
  - `mvn verify -Palpha`
  - `mvn verify -Pbeta`
- app 测试按系统属性 `assembly.entity` 门控（app/pom.xml 由 profile 注入），IDE 单跑需加 `-Dassembly.entity=alpha|beta`

## 运行

- `SPRING_PROFILES_ACTIVE=alpha java -jar app/target/app-*-alpha.jar`（beta 对称）
- 三个开关成对：构建 profile ↔ `SPRING_PROFILES_ACTIVE` ↔ `platform.entity`，漂移则启动失败（PolicyRegistry fail-fast）。`@ForEntity`（5.10.1 已落地）直接读 `platform.entity` 决定激活——`SPRING_PROFILES_ACTIVE` 通过激活 `application-{entity}.yaml` 间接提供 `platform.entity`，三者仍成对
- 扩展点实现统一用 `@ForEntity(EntityType.ALPHA|BETA)` 限定（不再用裸 `@Profile`）；ArchUnit `beMetaAnnotatedWith(Conditional.class)` 守护；契约基类 `PricingPolicyContractTest` 防 `@ForEntity` value 与 `supports()` 漂移

## 硬性约束（改动前必读）

- 依赖单向：`entity-* → platform-core`，`app → platform-core`；core 禁依赖实体模块、实体模块互禁依赖（Maven Enforcer 强制）
- `application`（除 `application.port`）与 `domain` 核心包禁止引用 `EntityType`/`EntityContext`（ArchUnit 强制）；差异一律走 `PricingPolicy` 式扩展点 + `@ForEntity` 限定（5.10.1 已落地，不再用裸 `@Profile`）
- `EntityContext` 为 ThreadLocal，仅限同步 Servlet 栈；`@Async` 必须经 `TaskDecorator` 传播
- 日志/指标必须带 `entity` 维度（MDC 由 `EntityContextFilter`/`TaskDecorator` 写入）；`traceId` 由 `TraceIdFilter` 注入（上游 `X-Trace-Id` 白名单校验），`@Async`/引擎任务经全量 MDC 快照随车传播
- Flowable delegate 一律继承 `EntityContextAwareDelegate`（Job 线程从流程变量重建上下文），禁止直接 `implements JavaDelegate`（实体模块 ArchUnit 强制）
- per-entity 配置（`application-{entity}.yaml`）、BPMN、迁移脚本一律放实体模块 resources，随 profile 裁剪；app 模块只放公共 `application.yaml`
- 事务内的副作用（审计等）走领域事件 + `@TransactionalEventListener(AFTER_COMMIT)`（文档 8.1 规则 11），禁止事务内直接触发

## Spring Boot 4 注意点（已踩过的坑）

- Flyway 自动配置在 `org.springframework.boot:spring-boot-flyway` 模块，需显式引入
- Web starter 用 `spring-boot-starter-webmvc`（旧名 `spring-boot-starter-web` 是兼容别名）
- `@AutoConfigureMockMvc` 在 `org.springframework.boot.webmvc.test.autoconfigure`（`spring-boot-webmvc-test` 模块）；`-test` starter 只含测试自动配置切片，JUnit/AssertJ/Awaitility 仍要显式引 `spring-boot-starter-test`
- 测试中覆盖 `application-*.yaml` 里的属性要用命令行参数（`app.run("--k=v")`），`SpringApplicationBuilder.properties()` 是 defaultProperties，优先级低于 yaml
- 工程存在多个 `TaskExecutor` bean 时（如 `applicationTaskExecutor` + `flowableJobExecutor`），无 qualifier 的 `@Async` 按类型取唯一执行器失败会**静默退回 `SimpleAsyncTaskExecutor`**——TaskDecorator 丢失、上下文断链且无报错。`applicationTaskExecutor` 必须 `@Primary`

## Flowable 注意点（第七章）

- Flowable 必须 >= 8.0（7.x 基于 Boot 3 不兼容；Enforcer 已锚定），当前 8.0.0
- ACT_* 表归 Flyway 管：`common/V3__flowable_engine_tables.sql`（提取自官方 jar 的 H2 DDL），`flowable.database-schema-update=false`；升级 Flowable 须人工核对表结构差异补脚本
- **ACT_ID_*（IDM）表不能漏**：引擎启动对 common/process/idm/eventregistry 四类 schema 逐一校验；IDM DDL 在独立的 `flowable-idm-engine` jar 里（不在流程引擎 jar），缺失时报极具误导性的 `db version is 5.99.0.0`（`IdmDbSchemaManager` 对缺表场景的升级起点默认值）
- Flowable 8 的 `SpringAsyncExecutor.setTaskExecutor` 接收引擎侧 `org.flowable.common.engine.api.async.AsyncTaskExecutor`，Spring 执行器需经 `org.flowable.common.spring.async.SpringAsyncTaskExecutor` 适配
- BPMN 在实体模块 `processes/` 目录，两实体同 key（`order-approval`）不同拓扑；引擎不做启动期 delegate 校验——装配冒烟兜底（同 key 唯一 + delegate 全装配）
- `infrastructure.engine` 是引擎适配层（允许接触 EntityContext，与 Filter 同级）；`application`/`domain` 仍禁止（ArchUnit 守护）

## .claude/rules 规范库（每会话必读纪律）

`.claude/rules/` 是跨项目通用的后端规范库，走 Claude Code 原生加载机制（官方文档 code.claude.com/docs/en/memory）：无 `paths` frontmatter 的文件每会话全量加载；带 `paths` 的文件在读写匹配文件时按需加载。**规范索引必须留在本文件内**——rules 目录的按需加载是否生效依赖客户端版本，本文件是全团队每会话必达的唯一可靠通道。

**纪律：开始编码 / review / 写测试前，先按下表通读与任务匹配的规范全文；review 结论必须逐条对照规范，不得仅凭记忆评审。**

| 规范文件 | 何时必读 |
| --- | --- |
| `java-coding-standard.md` | 任何 Java 编码/review：编码规范 + 契约编程 + 对象健身操 |
| `code-review.md` | 任何 review：检查清单 |
| `architecture.md` | 新增业务模块、四层分包决策（domain/application/infrastructure/interfaces） |
| `api-conventions.md` / `validation.md` | 新增 REST 端点、入参校验 |
| `exception-handling.md` | 异常设计（`BusinessException`/`ApiResponse`） |
| `service-conventions.md` | Service 编写 |
| `logging.md` | 日志、MDC、脱敏 |
| `db-conventions.md` / `db-migration.md` | JPA 实体、Flyway 迁移 |
| `test-conventions.md` / `integration-test-guide.md` / `tdd-workflow.md` / `contract-test.md` | 编写任何测试 |
| `downstream-conventions.md` | 下游 HTTP 调用（RestClient/WireMock） |
| `tech-stack.md` | 依赖与 starter 变更 |

### 骨架适用范围

本骨架是架构演示工程：`core.{context,interfaces,application,domain,infrastructure}` 分包与评审红线以本文件「硬性约束」+ README「Review 硬性规则」+ ArchUnit/Enforcer 护栏为准，**不套用四层分包**；`BusinessException`/`ApiResponse`、Contract Test、WireMock MockFactory 等规范项**面向后续新增业务模块**，引入对应能力时再生效；`java-coding-standard`/`logging`/`db-*`/`test-*` 等通用规范**现在即生效**。
