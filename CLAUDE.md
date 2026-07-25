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
- 三个开关成对：构建 profile ↔ `SPRING_PROFILES_ACTIVE` ↔ `platform.entity`，漂移则启动失败（PolicyRegistry fail-fast）

## 硬性约束（改动前必读）

- 依赖单向：`entity-* → platform-core`，`app → platform-core`；core 禁依赖实体模块、实体模块互禁依赖（Maven Enforcer 强制）
- `core.service` 包禁止引用 `EntityType`/`EntityContext`（ArchUnit 强制）；差异一律走 `PricingPolicy` 式扩展点 + `@Profile` 限定
- `EntityContext` 为 ThreadLocal，仅限同步 Servlet 栈；`@Async` 必须经 `TaskDecorator` 传播
- 日志/指标必须带 `entity` 维度（MDC 由 `EntityContextFilter`/`TaskDecorator` 写入）
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
- `core.flow` 是引擎适配层（允许接触 EntityContext，与 Filter 同级）；`core.service` 仍禁止（ArchUnit 守护）

## .claude/rules 适用范围

`.claude/rules/` 是跨项目通用的后端规范（四层架构分包、`BusinessException`/`ApiResponse`、Contract Test、WireMock MockFactory 等），**面向后续新增的业务模块**。本骨架是架构演示工程：`core.{context,policy,service,flow,...}` 分包与评审红线以本文件「硬性约束」+ README「Review 硬性规则」+ ArchUnit/Enforcer 护栏为准，不套用四层分包，骨架代码未实现的规范项（Contract Test、下游 Mock 等）在引入对应能力时再生效。
