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

## Spring Boot 4 注意点（已踩过的坑）

- Flyway 自动配置在 `org.springframework.boot:spring-boot-flyway` 模块，需显式引入
- `@AutoConfigureMockMvc` 在 `org.springframework.boot.webmvc.test.autoconfigure`（`spring-boot-webmvc-test` 模块）
- 测试中覆盖 `application-*.yaml` 里的属性要用命令行参数（`app.run("--k=v")`），`SpringApplicationBuilder.properties()` 是 defaultProperties，优先级低于 yaml
