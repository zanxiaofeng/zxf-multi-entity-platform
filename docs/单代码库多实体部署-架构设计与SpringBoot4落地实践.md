# 单代码库多实体部署：架构设计与 Spring Boot 4 落地实践

> 适用场景：同一套代码基线，各自独立部署，为两种相似业务的实体提供服务（产品变体架构）。
> 目标：差异被隔离、内核可演进、维护成本不随实体数量线性增长。

---

## 目录

> 仅列章节与重点子章节（模式落点 / 纪律章节）；完整子章节层级（如 5.10.1~3、5.11.1~2、6.2.1~2、6.3.1~4、7.5.1~4）见正文标题。

- [一、问题本质与核心原则](#一问题本质与核心原则)
- [二、架构设计层面的关键注意点](#二架构设计层面的关键注意点)
- [三、可用的架构与设计模式](#三可用的架构与设计模式)
- [四、健康度判断标准](#四健康度判断标准)
- [五、Spring Boot 4 落地骨架（Demo）](#五spring-boot-4-落地骨架demo)
  - [5.2.6 @Scheduled 的双部署语义](#526-scheduled-的双部署语义per-entity-还是-global)
  - [5.7 装配冒烟测试：防交叉污染 + 负例](#57-装配冒烟测试防交叉污染--负例)
  - [5.7.1 本地开发体验](#571-本地开发体验一套镜像两个实例独立数据库)
  - [5.10 装配与构建强化](#510-装配与构建强化)
  - [5.10.4 Maven 依赖收敛](#5104-maven-依赖收敛两个产物的依赖面必须可控)
  - [5.11 可观测性深化：三支柱打实体标签](#511-可观测性深化三支柱打实体标签)
  - [5.11.3 日志：MDC 键命名规范](#5113-日志mdc-键命名规范与-pattern-统一)
- [六、数据迁移、契约治理与配置一致性](#六数据迁移契约治理与配置一致性)
  - [6.2 契约治理与演进](#62-契约治理与演进)
  - [6.3 配置治理](#63-配置治理)
  - [6.4 扩展字段：结构通用、校验差异](#64-扩展字段结构通用校验差异)
  - [6.5 发布编排、灰度与回滚](#65-发布编排灰度与回滚)
- [七、Flowable 流程引擎集成](#七flowable-流程引擎集成)
  - [7.6 优雅停机与 Flowable 协调](#76-优雅停机与-flowable-协调)
- [八、Review 检查清单与工程护栏](#八review-检查清单与工程护栏)
  - [8.4 扩展点契约测试基类](#84-扩展点契约测试基类)
  - [8.5 EntityContext 泄漏防护兜底](#85-entitycontext-泄漏防护兜底)
  - [8.6 SPI 破坏性变更管理](#86-spi-破坏性变更管理)
- [九、演进方向](#九演进方向)
  - [9.1 ScopedValue 演进（JDK 25+）](#91-应用层上下文从-threadlocal-演进到-scopedvaluejdk-25)
  - [9.2 Spring Modulith 事件治理与测试切片](#92-spring-modulith事件治理与测试切片边界仍以-maven--archunit-为主)
  - [9.3 AOT / GraalVM 原生镜像兼容性预留](#93-aot--graalvm-原生镜像兼容性预留)
  - [落地路线图（优先级）](#落地路线图优先级)

---

## 一、问题本质与核心原则

这个问题本质是**产品变体（Product Variant）架构**：同一份代码基线，通过部署形态和差异化配置服务两类业务实体。做得好是平台化，做不好就是 if-else 地狱和双重维护。

核心原则：**差异越早（越靠近边缘）被识别和路由，核心代码越干净。**

最怕的情况是差异渗透到领域模型深处——每个聚合、每个服务里都有 `if (entityType == "A")`，此时共用代码库的收益已经被维护成本抵消。

---

## 二、架构设计层面的关键注意点

### 2.1 先识别差异发生的维度，再决定隔离位置

两套业务的差异通常落在五个维度，对应不同的隔离手段：

| 差异维度 | 典型例子 | 隔离手段 |
| --- | --- | --- |
| 数据模型 | 实体字段不同、校验规则不同 | Schema 版本化、扩展字段（EAV/JSONB/动态列）、或独立模块 |
| 业务流程 | 审批链路不同、状态机不同 | 轻量：策略模式 / 管道（步骤增删，见 5.8）；重量：工作流引擎（Camunda / Flowable，第七章） |
| 外部集成 | 对接不同的上下游系统 | SPI / 适配器（Adapter）模式，按部署装配不同实现 |
| 界面/交互 | 不同表单、不同展示 | 前端独立部署，或表单 Schema 驱动渲染 |
| 运维/合规 | 数据隔离级别、审计要求、灾备等级不同 | 部署拓扑 + 基础设施配置，不进代码 |

### 2.2 控制"配置 vs 代码"的边界

- 能用配置表达的差异（开关、阈值、映射表）→ 放配置中心或数据库，不进代码。
- 涉及行为逻辑的差异 → 用代码扩展点（策略/SPI），不要用字符串配置拼接逻辑——那是不能编译期检查、不能测试的最差形态。
- 涉及结构的差异（数据模型、流程拓扑）→ 谨慎评估是否真该共用同一套代码库。结构性差异太大时，强行共用代码库的维护成本高于"拆成两个仓库、共享平台层"。

### 2.3 明确共享内核（Shared Kernel）的边界

一套代码库要成立，必须划出不允许分叉的核心层：

- 领域模型中真正通用的概念（订单、账户、文档这类）
- 横切能力：安全、审计、幂等、事务、消息、PDF 生成、监控埋点
- 基础设施适配层

核心层的演进要按平台的标准治理：语义化版本、变更评审、对两套部署都有兼容性义务。**差异代码只允许出现在扩展点实现里。**

### 2.4 运行时隔离与部署形态

各自部署天然获得进程级隔离，注意三点：

- **产物同源、按需裁剪**：同一代码库产出，但按实体裁剪打包（见 5.4）。注意：按 Maven profile 裁剪后打出的 jar 内容不同，**是两个不同的二进制产物**，而非"同一 artifact"——CI 必须对每个产物分别构建与冒烟，不能拿其中一个产物的测试结果给另一个背书。只有在合规/镜像瘦身无要求时，才考虑"全量打包 + 运行期按配置激活"的单产物模式。
- **数据库**：**本项目决策为严格分库（database-per-entity）**——每个实体独立库，独立升级 schema、独立备份恢复、独立扩容，没有任何共享表/共库形态，因此也无需 RLS、`@Filter` 之类的共库越权防护。一般性讨论上，共库只在数据量小且强关联时可考虑，但不适用于本项目；下文 6.1 的 `common` 迁移目录指"两个实体库内**结构相同、各自一份**的通用表"，不是共享物理表。
- **版本漂移**：两套部署允许存在版本差吗？如果允许，共享的消息契约、API 契约、数据库 schema 变更必须前后兼容（expand-and-contract 模式，见第六章）。

### 2.5 可观测性必须打实体维度

所有日志、指标、Trace 都带上 `entity` 标签。两套部署共用一套监控体系（如 Splunk）时，没有这个维度等于无法排障；告警规则也应按实体维度分别配置阈值。

### 2.6 测试策略

每条原则在后文有对应落地章节，按索引查阅：

- **共享内核：一套契约测试 + 单元测试** → 契约基类见 8.4（实体模块继承即回归），SPI 二进制兼容守门见 8.6。
- **每个实体：针对其扩展点实现的集成测试**（Testcontainers 起真实依赖）→ 容器化测试基建见 5.7.1。
- **CI 装配矩阵：每个构建产物 × 对应 profile**，防止某实体配置搞坏另一实体装配——共用代码库部署最常见的线上事故来源 → 冒烟矩阵与负例见 5.7，依赖面 diff 见 5.10.4。
- **负例必须可断言**：profile 与 `platform.entity` 不一致时启动必须失败 → `ApplicationContextRunner` 完整示例见 5.7。
- **流程引擎装配同等级冒烟**：delegate 装配、流程/事件定义同 key 唯一 → 见 7.4。
- **架构护栏自身不自欺**：ArchUnit 规则防静默失效（自检测试）→ 见 8.3。

---

## 三、可用的架构与设计模式

按投入产出排序：

1. **策略模式 + 注册表（Strategy + Registry）**：最基础的差异隔离。行为差异封装为接口实现，按实体标识从注册表选取。Spring 中即按条件注入的组件集合 + 一个 `EntityContext`。
2. **SPI / 插件化（Spring 条件装配，后期升级 ServiceLoader）**：策略类膨胀时，把实体专属代码收进独立 module，主工程只依赖接口，打包时按实体裁剪。实体 B 的代码甚至不进入实体 A 的镜像。
3. **管道/过滤器（Pipeline）**：流程步骤的增删差异（A 多一步风控，B 多一步审计）用管道编排，优于 if-else 嵌入主流程。Demo 见 5.8.1。
4. **模板方法（Template Method）**：主流程骨架固定、个别步骤不同。适合差异点少且稳定的业务。Demo 仅作边界示范，见 5.8.2。
5. **特性开关（Feature Toggle）**：仅用于发布控制和临时差异，不要用它承载长期业务差异——开关数量指数增长，测试矩阵爆炸。开关必须有 TTL、责任人和到期报警约定，逾期不清理即技术债工单。**Demo 刻意不提供示例**（防止反向诱导），理由见 5.8。
6. **流程引擎外置（Camunda / Flowable）**：差异主要在流程拓扑时，把流程定义外置为 BPMN，每个实体部署自己的流程定义文件，代码只剩任务实现。完整方案见第七章。
7. **配置即数据 + Schema 驱动**：表单、校验规则、字段映射用 Schema 描述存库，代码只写解释器。适合差异高频但浅层的场景。Demo 见 5.8.3。

---

## 四、健康度判断标准

定期检查一个问题：**如果把某一套部署删掉，代码里要删多少东西？**

- 理想情况：删一个 module / 一组配置 / 一组流程定义即可，核心层零改动。
- 警告信号：
  - 核心层散落实体判断；
  - 通用表结构里有大量仅一个实体使用的字段（严格分库下体现为 common 目录被单实体字段污染）；
  - 升级一个实体需要回归另一个实体的全部功能。

出现警告信号，说明差异已侵入共享内核。该强制收敛到 SPI 边界内，或考虑把变体拆成独立服务（共享平台层而非共享应用层）。

---

## 五、Spring Boot 4 落地骨架（Demo）

> 技术基线：Spring Boot 4.x（Spring Framework 7，2025-11 GA），**JDK 最低 17，推荐 21+**（虚拟线程），Maven 多模块。
> 核心思路：共享内核只做抽象，差异代码进独立 SPI 模块，运行时通过统一实体配置 + EntityContext 装配与路由。

### 5.0 Spring Boot 4 迁移检查项

从 Spring Boot 3 迁移或新建时，直接影响本骨架的变化：

- **JDK 基线 17**（非 21）；21+ 用于虚拟线程（`spring.threads.virtual.enabled=true`），但虚拟线程下 ThreadLocal 语义不变，上下文传播问题依然存在（见 5.2.3）。
- **全面模块化拆包**：Boot 4 重排了自动配置与相关类的模块/包归属，自定义注册的 `FilterRegistrationBean`、自动配置类的 import 需逐一核对。
- **Jackson 3**：默认 JSON 实现升级为 Jackson 3（`tools.jackson` 包），共享内核中的自定义序列化器、`ObjectMapper` 配置需重写适配。
- **嵌入式容器**：Undertow 已移除，基线为 Tomcat 11 / Jetty 12（Servlet 6.1）。
- **JSpecify 空安全**：框架 API 全面采用 JSpecify 注解；本骨架中可空返回值（如上下文查询）应标注 `@Nullable`，让 IDE 与静态检查在编译期拦截 NPE。
- **@ConfigurationProperties**：对 public 字段的宽松绑定已移除，统一使用构造器绑定或 setter。

### 5.1 模块结构

```text
multi-entity-platform/
├── platform-core/          # 共享内核：抽象、上下文、横切能力（不允许分叉）
├── entity-alpha/           # 实体 A 的 SPI 实现（独立 module）
├── entity-beta/            # 实体 B 的 SPI 实现
└── app/                    # 启动模块，构建时按实体裁剪打包
```

依赖方向严格单向：`entity-* → platform-core`，`app → platform-core`。
`app` 通过 Maven profile 决定打包哪个实体模块——实体 B 的类不进入实体 A 的镜像。代价是：两个实体产出两个不同的二进制（见 2.4），CI 需分别构建验证。

#### 5.1.1 模块内部：六边形架构 × DDD 四层分包

Maven 模块解决"实体间/内核间"的边界；**模块内部的包结构用六边形架构（Ports & Adapters）组织，与 DDD 四层对应**——六边形的"端口"正是多实体方案里扩展点（SPI）的天然归宿：

| 六边形 | DDD 层 | 包 | 职责 | 实体差异允许？ |
| --- | --- | --- | --- | --- |
| 端口（Port） | 领域层 | `domain.port` | 扩展点接口、Repository 接口、出站服务接口 | 接口本身不允许分叉 |
| 领域核心 | 领域层 | `domain.model` / `domain.service` | 聚合、值对象、领域服务、领域事件 | ❌ 绝对禁止 |
| 用例编排 | 应用层 | `application` | 应用服务（事务边界）、命令/查询对象 | ❌ 禁止 |
| 出站适配器（Driven Adapter） | 基础设施层 | `infrastructure.persistence` / `.messaging` / `.engine` | Repository 实现、MQ、Flowable 集成 | 通用实现在 core；**差异实现放实体模块** |
| 入站适配器（Driving Adapter） | 接口层 | `interfaces.rest` / `.consumer` | Controller、消息监听、Filter | ❌ 禁止（差异在边缘已路由） |
| 组合根 | — | `app` | 启动、装配、配置注入 | ✅ 按 profile 装配 |

重组后的包结构：

```text
platform-core/
└── src/main/java/com/example/platform/
    ├── interfaces/                    # 入站适配器（Driving / DDD 接口层）
    │   ├── rest/                      OrderController、异常映射 @RestControllerAdvice
    │   ├── consumer/                  消息监听器（Kafka inbound → 应用服务）
    │   └── filter/                    EntityContextFilter（含 MDC，5.2.2）
    ├── application/                   # 应用层：用例编排，无业务规则
    │   ├── OrderApplicationService    # 事务边界在此（@Transactional）
    │   ├── command/                   CreateOrderCommand 等
    │   ├── assembler/                 DTO ↔ 领域对象转换
    │   └── port/                      ★ 端口解析机制（含 Spring 装配，非纯领域）
    │       ├── PolicyRegistry         # 策略注册表（5.2.5，@Component 组合机制）
    │       └── OrderPipeline          # 管道编排器（5.8.1）
    ├── domain/                        # 领域层：纯 Java，零框架注解
    │   ├── model/                     Order 聚合、Money 值对象、OrderStatus
    │   ├── service/                   跨聚合领域服务
    │   ├── event/                     OrderCreated 等领域事件
    │   └── port/                      ★ 端口 = 纯契约接口，零框架注解
    │       ├── PricingPolicy          # 差异端口（实体模块实现）
    │       ├── OrderStep              # 管道步骤接口（5.8.1）
    │       └── OrderRepository        # 持久化端口（core 基础设施实现）
    ├── infrastructure/                # 出站适配器（Driven / DDD 基础设施层）
    │   ├── persistence/               OrderJpaAdapter 实现 domain.port.OrderRepository
    │   ├── messaging/                 Outbox relay、事件发布适配器
    │   ├── engine/                    Flowable 集成（OrderApprovalService，7.1）
    │   └── observation/               MDC/指标 entity 打标（2.5）
    └── context/                       EntityContext、EntityType（横切最小集）

entity-alpha/
└── com/example/entity/alpha/
    ├── adapter/                       # ★ 实体 = 一组出站适配器实现
    │   ├── AlphaPricingPolicy         # implements domain.port.PricingPolicy
    │   └── RiskCheckStep              # implements domain.port.OrderStep
    └── process/                       BPMN、Event Registry 通道（resources）
app/
└── PlatformApplication + 配置          # 组合根：唯一知道"全部零件"的地方
```

**关键对应关系**：

1. **扩展点 = 端口，实体实现 = 适配器**。`PricingPolicy` 从"策略接口"重新定性为 domain port——这不是改名，而是明确了它的架构身份：内核（六边形内部）只依赖端口，实体模块是插到端口上的适配器。多实体隔离因此复用了六边形最成熟的纪律：**适配器可换，端口契约稳定**。注意区分两类适配器：**资源型出站适配器**（JPA/MQ，访问外部资源，归 core 的 infrastructure）与**行为型扩展点适配器**（Pricing/Step，只提供差异化算法，归实体模块）——前者允许依赖外部客户端，后者应保持纯计算。另注意 `domain.port` 只放**纯契约接口（零框架注解）**；带 Spring 装配语义的端口解析机制（`PolicyRegistry`、`OrderPipeline`）属组合关注点，归 `application.port`，否则与"领域层零框架注解"及下方 ArchUnit 规则自相矛盾。
2. **组合根在 app**。app 是唯一既依赖 core 又依赖实体模块的地方，负责"把哪个适配器插到哪个端口"——Maven profile（5.4）就是组合根的构建期表达。
3. **领域层零实体感知有了新的强制手段**：ArchUnit 洋葱架构规则替代 8.3 的单点规则：

```java
// ArchUnit ≥ 0.21；onionArchitecture 的层方法固定为
// domainModels / domainServices / applicationServices / adapter(名称, 包)
@ArchTest
static final ArchRule 六边形分层 =
        Architectures.onionArchitecture()
                .domainModels("..domain.model..", "..domain.event..")
                .domainServices("..domain.service..", "..domain.port..")
                .applicationServices("..application..")
                .adapter("rest", "..interfaces.rest..")
                .adapter("consumer", "..interfaces.consumer..")
                .adapter("persistence", "..infrastructure.persistence..")
                .adapter("engine", "..infrastructure.engine..")
                .adapter("messaging", "..infrastructure.messaging..")
                .withOptionalLayers(true);
// 注：context 包（EntityContext）是横切的"隐式参数"，等效于显式方法参数，
// 置于洋葱最外层/豁免，不作为层参与规则。

@ArchTest
static final ArchRule 领域核心零实体感知 =
        // 范围为 model/service/event：domain.port 的 supports() 以 EntityType 声明适配实体
        // （5.2.4），属端口契约的一部分——若覆盖整个 ..domain.. 会与本布局自相矛盾
        noClasses().that().resideInAPackage("..domain.model..")
                .or().resideInAPackage("..domain.service..")
                .or().resideInAPackage("..domain.event..")
                .should().dependOnClassesThat().areAssignableTo(EntityType.class);

@ArchTest
static final ArchRule 应用层仅端口解析器可感知实体 =
        noClasses().that().resideInAPackage("..application..")
                .and().resideOutsideOfPackage("..application.port..")
                .should().dependOnClassesThat().areAssignableTo(EntityType.class);
```

> `PolicyRegistry` / `OrderPipeline` 是唯一的"实体感知"组件（通过 `EntityContext` 读取当前实体做端口解析），故归 `application.port` 并在规则中单列豁免。若希望连这点隐式依赖也消除，可将注册表纯函数化——`pricing(EntityType type)` 显式入参，由调用方（应用服务）从 `EntityContext` 取值传入；代价是应用层方法签名多一个参数，收益是注册表可脱离 ThreadLocal 单测。团队按口味二选一，规则相应调整。

4. **与 DDD 四层的映射说明**：DDD 经典四层（接口/应用/领域/基础设施）中，六边形把"基础设施"拆为入站/出站适配器，并把 DDD 分层容易模糊的"接口层 vs 基础设施层"统一为"适配器"概念。本方案包名按六边形命名（`interfaces`/`infrastructure`），分层语义按 DDD——二者在此是同一结构的两种视角，不是叠加两套结构。
5. **实体模块的定位升级**：`entity-alpha` 从"SPI 实现包"升级为"**该实体的适配器包**"——它不仅能实现定价端口，还可以提供专属持久化适配器（实体专属表的 Repository 实现）、专属外部系统适配器（2.1"外部集成"维度的归处），全部通过 `@ForEntity`（5.10.1）装配插到 core 端口上。
6. **既有代码的归位**：`OrderService`（5.5）→ `application.OrderApplicationService`；`EntityContextFilter` → `interfaces.filter`；`PolicyRegistry` / `OrderPipeline` → `application.port`（含 Spring 装配的端口解析机制）；`OrderRepository` 接口 → `domain.port`，JPA 实现 → `infrastructure.persistence`。5.2 节的示例代码按此包归属理解（示例中省略 package 声明）。

> 注意 DDD 战略层面的一条边界：两个实体**共享同一个限界上下文**（共用代码库方案成立的前提，与数据库分库无关）。当健康度检查（第四章）触警、实体拆分为独立服务时，拆分线就是新的限界上下文线——此时共享内核降级为共享平台层（第九章），端口/适配器结构使拆分的物理成本最小：适配器模块原样搬走。但端口契约**不会自动变成**跨服务 API 契约——端口只是拆分的**接缝候选**，拆分时需在其上新增 DTO、版本化、幂等与容错层（进程内接口是同步、共享事务、共享领域对象的；跨服务契约这些假设全部断裂，见 6.2 契约治理）。

### 5.2 platform-core：共享内核

#### 5.2.1 实体标识与上下文

```java
public enum EntityType { ALPHA, BETA }
```

> 注意：实体枚举放在核心层意味着每加一个实体都要改核心层。实体数预期会增长时，注册表键可直接用 `String`（`platform.entity` 的值），新增实体零改动核心层。两个实体的阶段枚举更简单，且编译期可穷举——按需选择。

```java
public final class EntityContext {

    private static final ThreadLocal<EntityType> HOLDER = new ThreadLocal<>();

    private EntityContext() {}

    public static void set(EntityType type) { HOLDER.set(type); }

    public static EntityType current() {
        return Optional.ofNullable(HOLDER.get())
                .orElseThrow(() -> new IllegalStateException("EntityContext 未初始化"));
    }

    public static void clear() { HOLDER.remove(); }
}
```

> **适用边界：EntityContext 仅限同步 Servlet 栈**（Tomcat/Jetty + Spring MVC）。WebFlux 下 `OncePerRequestFilter` 不生效，需改用 `WebFilter` + Reactor Context。此约束写入 Review 检查清单。

#### 5.2.2 上下文解析（边缘识别，一次路由）

关键点：上下文设置与 MDC 打标放在**同一个 Filter 的同一 try/finally 生命周期内**，彻底消除两个 Filter 执行顺序不确定导致的 NPE / 日志串实体问题（Tomcat 线程池复用下，漏清 MDC 会让 A 实体的请求日志带上 B 的标）。

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class EntityContextFilter extends OncePerRequestFilter {

    private final PlatformProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 部署级实体（来自配置）优先；多实体混部时可改为从 Header/Token 解析
        EntityContext.set(properties.entity());
        MDC.put("entity", properties.entity().name()); // 日志带实体维度，Splunk 可按 entity 分别告警
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("entity");
            EntityContext.clear();
        }
    }
}
```

```java
/** 当前部署服务的实体（唯一事实来源）。record 构造器绑定：不可变、无 setter（对象健身操军规 9） */
@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(EntityType entity) {

    public PlatformProperties {
        Assert.notNull(entity, "platform.entity 必须配置");
    }
}
```

> **MVC 异步端点的上下文盲区**：Controller 返回 `Callable`/`WebAsyncTask` 时，`OncePerRequestFilter` 的 finally 在**同步分发阶段**就执行完毕——`EntityContext`/MDC 被清理后，业务逻辑才在异步线程上运行，上下文丢失。两种处置，团队二选一并写入规范：(a) 注册 `CallableProcessingInterceptor`，在其 `preProcess` 中于异步线程恢复上下文、`postProcess` 中清理；(b) 约定实体感知代码（`PolicyRegistry`、实体差异逻辑）**不进入异步 Controller 路径**——异步端点只做与实体无关的 IO 编排，计价等动作在同步阶段完成。Demo 采用 (b)，因异步端点在本骨架中极少且多为非实体感知操作。

> 本节的边缘 ThreadLocal 机制维持不变；应用层内部新建虚拟线程做结构化并发的场景，JDK 25 后可演进到 ScopedValue，见 9.1。

> **`traceId` 由独立的 `TraceIdFilter` 注入**（`@Order(HIGHEST_PRECEDENCE)`，先于 `EntityContextFilter`）：从上游 `X-Trace-Id` 头取值并做白名单校验（防日志注入 / 响应头分裂 CRLF），缺失则生成 UUID；写入 MDC + 回写响应头，try/finally 清理。`EntityContextFilter` 只管 `entity` 键，`TraceIdFilter` 只管 `traceId` 键——两者写不同 MDC key、各自清理，5.11.3 logback pattern 中的 `%X{traceId:-}` 与 `%X{entity:-none}` 分别由这两个 Filter 兜底。

#### 5.2.3 上下文传播（异步场景）

ThreadLocal 不跨线程传播。以下场景必须显式处理，否则 `PolicyRegistry` 会在错误/空上下文中取策略——对定价类逻辑是资损级风险：

- **`@Async` / 自定义线程池**：用 `TaskDecorator` 传播：

```java
@Configuration
public class AsyncConfig {

    @Bean
    public TaskDecorator entityContextPropagator() {
        return runnable -> {
            EntityType entity = EntityContext.current();
            return () -> {
                EntityContext.set(entity);
                MDC.put("entity", entity.name());
                try {
                    runnable.run();
                } finally {
                    MDC.remove("entity");
                    EntityContext.clear();
                }
            };
        };
    }
}
```

- **`CompletableFuture` 手工提交线程池**：同样需包装，或显式把 `EntityType` 作为参数传入异步方法（更显式，推荐核心业务路径使用）。
- **虚拟线程**：`ThreadLocal` 在虚拟线程上语义不变，上述传播方案同样适用。
- **消息消费（Kafka/Rabbit Listener）**：消费线程与 Web 线程无关，需从消息头解析实体并设置上下文，且实体标识必须写入消息契约（见 6.2）。
- **WebFlux**：本方案不适用，需改用 `WebFilter` + Reactor Context 的 `contextWrite`/`deferContextual`。

> 池线程（`@Async`、消息监听）的 TaskDecorator/消息头传播机制保留不动；ScopedValue 演进（9.1）只覆盖"应用层内部新建虚拟线程"的结构化并发场景，不替代本节。

#### 5.2.4 差异行为的契约（扩展点）

```java
/**
 * 所有按实体差异的行为都收敛为这样的接口。
 * 共享内核只依赖接口，不知道有几个实现。
 */
public interface PricingPolicy {

    EntityType supports();

    Money calculate(Order order);
}
```

#### 5.2.5 策略注册表（替代 if-else，启动期 fail-fast）

```java
@Component
public class PolicyRegistry {

    private final Map<EntityType, PricingPolicy> pricingPolicies;

    public PolicyRegistry(List<PricingPolicy> policies, PlatformProperties properties) {
        // 显式 merge 函数：同实体出现双实现时抛带上下文的异常，
        // 而非让 Collectors.toUnmodifiableMap 走默认路径抛裸 "Duplicate key"（无实体名、无实现类名，排查成本高）
        this.pricingPolicies = policies.stream()
                .collect(Collectors.toUnmodifiableMap(PricingPolicy::supports, Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "实体 %s 装配了重复的 PricingPolicy 实现：%s 与 %s，请检查实体模块依赖与 @ForEntity 限定"
                                            .formatted(a.supports(), a.getClass().getName(), b.getClass().getName()));
                        }));
        // 启动期防护：当前实体必须有且仅有一个实现装配，否则直接启动失败
        if (!pricingPolicies.containsKey(properties.entity())) {
            throw new IllegalStateException(
                    "当前实体 %s 未装配 PricingPolicy，请检查构建产物（Maven profile）与 platform.entity 是否一致，"
                            + "以及扩展点实现是否标注了 @ForEntity(%s)"
                            .formatted(properties.entity(), properties.entity()));
        }
    }

    /** 对外只暴露完整行为（对象健身操军规 4：调用方一行一个点） */
    public Money priceFor(Order order) {
        var current = EntityContext.current();
        var policy = Optional.ofNullable(pricingPolicies.get(current))
                .orElseThrow(() -> new IllegalStateException("当前实体未装配 PricingPolicy: " + current));
        return policy.calculate(order);
    }

    /** 冒烟测试等装配校验场景使用 */
    boolean hasPolicy(EntityType type) {
        return pricingPolicies.containsKey(type);
    }
}
```

> `List<PricingPolicy>` 由 Spring 注入容器中全部实现。若配置漂移（`platform.entity=alpha` 但忘了激活 alpha profile），容器里实现缺失——构造器内的校验让应用在**启动期立即失败**，而不是运行期第一次计价时才炸。

#### 5.2.6 @Scheduled 的双部署语义：per-entity 还是 global

定时任务是双部署下最容易被忽视的语义陷阱（与 5.2.3 异步传播同属运行时语义）：同一个 `@Scheduled` 方法在**两套部署上都会触发**。先判定任务作用域，再决定要不要选主：

| 作用域 | 判定 | 双跑后果 | 处置 |
| --- | --- | --- | --- |
| **per-entity（默认）** | 任务只读本实体库、只产出本实体数据（如"清理本实体过期订单"） | 无——两套部署各扫各的库，双跑即正确 | 什么都不用做 |
| **global** | 任务作用于共享外部资源（下游系统对账、全局报表、清理对象存储） | 双跑 = 重复执行，下游可能不幂等 | 必须选主，保证同一时刻只有一侧执行 |

global 任务用 ShedLock 选主。**注意：严格分库下没有共享库可放锁表**（JDBC 锁存储不可用），锁存储二选一：

```java
// 方案一：Redis 锁存储（团队已有 Redis 时首选）
@Bean
public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
    // 第二参数 "platform-locks" 是 Redis key 前缀（所有锁键的命名空间）；
    // 具体锁名在 @SchedulerLock(name = "...") 上按任务命名——见下方 globalReconciliation
    return new RedisLockProvider(connectionFactory, "platform-locks");
}
```

```java
// 方案二：K8s Lease 锁存储（无中间件依赖，shedlock-provider-kubernetes-fabric8，基于 Lease 对象）
@Bean
public LockProvider lockProvider(KubernetesClient client) {
    // 第二参数同理：Lease 对象的命名空间前缀，锁名在 @SchedulerLock(name = "...") 上
    return new KubernetesLockProvider(client, "platform-locks");
}
```

```java
@Scheduled(cron = "0 0 3 * * *")
@SchedulerLock(name = "globalReconciliation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
public void globalReconciliation() {
    // 同一时刻仅一套部署持有锁；非持有者秒退
}
```

配套纪律（进 8.1 硬规则）：**PR 中含 `@Scheduled` 必须注明任务作用域；声明 global 的任务必须带选主机制**，冒烟矩阵（5.7）中对 global 任务断言锁存储可达。锁名全局唯一、按任务命名，不按实体命名——锁的意义恰是跨实体互斥。

### 5.3 实体模块：SPI 实现

entity-alpha：

```java
@Component
@Profile("alpha")
public class AlphaPricingPolicy implements PricingPolicy {

    @Override
    public EntityType supports() { return EntityType.ALPHA; }

    @Override
    public Money calculate(Order order) {
        // Alpha 专属计价逻辑
        throw new UnsupportedOperationException("demo");
    }
}
```

Alpha 专属的流程定义、校验规则、外部系统适配器同样放本模块，用 `@Profile("alpha")` 限定。

entity-beta 结构完全对称，`supports()` 返回 `BETA`。

> 仅本节、5.8.1、7.2 的示例沿用 `@Profile` 便于理解最朴素的激活机制（其余章节示例已直接用 `@ForEntity`）；**生产代码统一用 `@ForEntity`**（5.10.1 已落地，ArchUnit 守护），8.1 第 2 条以复合注解为准。`@ForEntity` 落地后 `platform.entity` 成为唯一激活开关源，6.3 中 `SPRING_PROFILES_ACTIVE` 与实体激活的成对要求随之取消（profile 仅保留环境用途）。

> **扩展点实现类不允许调用对方模块的任何类**——Maven 依赖边界从构建期保证这一点（见 8.2 Enforcer 配置）。

**实体身份的唯一事实来源规约**：`platform.entity` 是唯一激活开关源。当前已落地 `@ForEntity`（5.10.1）作为扩展点统一激活注解——扩展点实现类用 `@ForEntity(EntityType.ALPHA)` 标注，底层 `EntityCondition` 读 `platform.entity` 决定是否激活。`SPRING_PROFILES_ACTIVE` 退化为仅承载环境（dev/staging/prod），不再与实体激活绑定。禁止再引入第三个开关源（如裸 `@Profile` / `@ConditionalOnProperty` 与 `@ForEntity` 混用），否则双轨漂移即事故。

### 5.4 app：启动模块与装配

#### 5.4.1 Maven profile 控制打包内容

```xml
<profiles>
    <profile>
        <id>alpha</id>
        <dependencies>
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>entity-alpha</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </profile>
    <profile>
        <id>beta</id>
        <dependencies>
            <dependency>
                <groupId>com.example</groupId>
                <artifactId>entity-beta</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </profile>
</profiles>
```

> 再次强调：`-Palpha` 与 `-Pbeta` 打出的 jar 内容不同，是**两个二进制产物**。发布流水线中两个产物各自打 tag、各自出 SBOM、各自过冒烟，互不背书。

#### 5.4.2 单一启动类，无实体感知

```java
@SpringBootApplication
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
```

#### 5.4.3 部署配置（K8s ConfigMap 注入）

```yaml
# application-alpha.yaml
platform:
  entity: alpha
spring:
  datasource:
    # 数据库选型由部署环境决定：示例为 Oracle（生产）；
    # 本地开发用 PostgreSQL（5.7.1 compose）；测试用 H2 内存库（CLAUDE.md「Flowable 注意点」）
    url: jdbc:oracle:thin:@//alpha-db:1521/ALPHA
```

部署清单里设置 `SPRING_PROFILES_ACTIVE=alpha,prod`。两套部署共用同一代码库与镜像构建流水线，只有环境变量和 ConfigMap 不同。

### 5.5 共享内核中的通用逻辑示例

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository repository;
    private final PolicyRegistry policies;

    @Transactional
    public Order create(CreateOrderCommand cmd) {
        var order = Order.from(cmd);          // 通用：结构
        var price = policies.priceFor(order); // 差异：委托扩展点（注册表封装"取策略+执行"）
        order.priceTo(price);
        return repository.save(order);
    }
}
```

`OrderService` 里没有任何实体判断——这是 review 时的硬性检查项。

### 5.6 单测中的 EntityContext

单元测试直接 set/clear，或针对核心服务传入显式参数：

```java
@BeforeEach
void setUp() { EntityContext.set(EntityType.ALPHA); }

@AfterEach
void tearDown() { EntityContext.clear(); }
```

若核心类频繁依赖静态上下文导致难测，可抽 `EntityProvider` 接口注入（`EntityContext` 退化为其实现），便于 Mockito mock——测试痛是信号，痛了就抽接口。

### 5.7 装配冒烟测试：防交叉污染 + 负例

```java
@SpringBootTest
@ActiveProfiles("alpha")
class AlphaAssemblySmokeTest {

    @Autowired
    private PolicyRegistry registry;

    @Autowired
    private List<PricingPolicy> policies;

    @Test
    void 只装配Alpha实现且注册表可解析() {
        assertThat(policies)
                .extracting(PricingPolicy::supports)
                .containsExactly(EntityType.ALPHA);
        assertThat(registry.hasPolicy(EntityType.ALPHA)).isTrue();
    }
}
```

```java
// 负例：激活开关源不一致时启动必须失败（platform.entity 与装配的 @ForEntity 实现不匹配）
// @SpringBootTest 无法在 @Test 内断言"启动失败"（启动失败即测试 ERROR，而非可断言的结果），
// 必须把"启动"变成可被断言的对象——ApplicationContextRunner 或 try-with-resources
// AnnotationConfigApplicationContext + assertThatThrownBy 都可，二选一。Demo 用后者。
class MisconfiguredAssemblyTest {

    @Configuration
    @EnableConfigurationProperties(PlatformProperties.class)
    @Import(PolicyRegistry.class)
    @ComponentScan("com.zxf.platform.alpha")   // 装配 alpha 包内的 @ForEntity(ALPHA) 实现
    static class TestAssembly {}

    @Test
    void 开关源不一致时上下文启动失败() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("alpha");
            TestPropertyValues.of("platform.entity=beta").applyTo(context.getEnvironment());
            context.register(TestAssembly.class);

            // platform.entity=beta 但 alpha 包内只有 @ForEntity(ALPHA) 实现 → 容器没有 PricingPolicy
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未装配 PricingPolicy");
        }
    }
}
```

CI 流水线（伪代码）：

```yaml
assembly-matrix:
  strategy:
    matrix:
      entity: [alpha, beta]
  steps:
    - mvn verify -P${{ matrix.entity }} -Dtest=*Assembly*Test
    - 构建并推送 entity-${{ matrix.entity }} 镜像
```

#### 5.7.1 本地开发体验：一套镜像、两个实例、独立数据库

最小 `docker-compose.yml` 让"同一镜像、差异全在环境变量"在本地即可演示——两个服务**共用同一镜像**，分别激活 alpha/beta，各挂独立数据库容器（严格分库的本地投影）：

```yaml
services:
  app-alpha:
    image: multi-entity-platform:latest
    environment:
      # 本地开发省略 prod profile（生产部署按 5.4.3 用 SPRING_PROFILES_ACTIVE=alpha,prod）；
      # alpha 仍需保留——激活 application-alpha.yaml 间接提供 platform.entity=alpha
      SPRING_PROFILES_ACTIVE: alpha
      SPRING_DATASOURCE_URL: jdbc:postgresql://db-alpha:5432/alpha
    ports: ["8081:8080"]
    depends_on: [db-alpha]
  app-beta:
    image: multi-entity-platform:latest
    environment:
      SPRING_PROFILES_ACTIVE: beta
      SPRING_DATASOURCE_URL: jdbc:postgresql://db-beta:5432/beta
    ports: ["8082:8080"]
    depends_on: [db-beta]
  db-alpha:
    image: postgres:16
    environment: { POSTGRES_DB: alpha, POSTGRES_PASSWORD: dev }
  db-beta:
    image: postgres:16
    environment: { POSTGRES_DB: beta, POSTGRES_PASSWORD: dev }
```

> 本地用一个镜像跑两侧是开发便利，不是生产形态——生产仍是两个独立构建产物（2.4）。

测试侧同步消除本地 DB 依赖：**Testcontainers + `@ServiceConnection`**，装配冒烟矩阵（5.7、7.4）在无本地数据库的机器上直接 `mvn verify`：

```java
@SpringBootTest
@ActiveProfiles("alpha")
class AlphaAssemblySmokeTest {

    @Container
    @ServiceConnection   // Boot 3.1+：自动注册 DataSource 连接信息，无需手工 @DynamicPropertySource
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    // ...
}
```

per-entity 的 Flyway locations（6.1）与冒烟测试共用同一个容器配置类（`@Testcontainers` 基类按 `platform.entity` 参数化），保证"冒烟跑过的迁移脚本"与"部署执行的迁移脚本"是同一份。

### 5.8 设计模式的 Demo 落地取舍

第三章的模式不应全部堆进 demo——demo 的价值是每种模式一个最小可运行示例，并示范**模式间的分工边界**。落地取舍如下：

| 模式 | 是否进 Demo | 说明 |
| --- | --- | --- |
| ① 策略 + 注册表 | ✅ 已有 | 骨架核心（`PricingPolicy` / `PolicyRegistry`） |
| ② SPI / 插件化 | ✅ 已有（半落地） | 独立 module + profile 裁剪已体现；ServiceLoader 动态发现属第九章演进，不提前做 |
| ③ 管道/过滤器 | ✅ 引入 | 解决"步骤增删"差异，与策略（"算法替换"）互补 |
| ④ 模板方法 | ⚠️ 精简引入 | 只作适用边界示范，差异点会增长就必须退化为策略 |
| ⑤ 特性开关 | ❌ 刻意不给代码 | 防止反向诱导——拿 Toggle 承载实体差异正是 if-else 地狱的入口 |
| ⑥ 流程引擎外置 | ✅ 已有 | 第七章 |
| ⑦ 配置即数据 + Schema 驱动 | ✅ 引入 | 浅层高频差异的归处，与策略构成分工对照 |

#### 5.8.1 管道：步骤增删差异

策略模式解决"同一件事算法不同"，管道解决"流程步骤增删不同"（A 多一步风控、B 多一步审计）。核心层定义管道与步骤接口，**步骤列表由装配决定，核心层零实体判断**：

```java
public interface OrderStep {

    String name();

    void execute(OrderContext ctx);
}

@Component
public class OrderPipeline {

    private final List<OrderStep> steps;

    public OrderPipeline(List<OrderStep> steps) {
        // Spring 注入当前实体装配的全部步骤，按 @Order（或 Ordered 接口）排序。
        // 与 5.2.5 同级 fail-fast：装配遗漏导致零步骤时注入空 List，启动即失败而非静默空转。
        Assert.notEmpty(steps, "当前实体未装配任何 OrderStep，请检查 profile 与 platform.entity");
        this.steps = steps;
    }

    public void run(OrderContext ctx) {
        steps.forEach(step -> step.execute(ctx));
    }
}
```

```java
// entity-alpha：比 beta 多一步风控
@Component
@Order(2)
@Profile("alpha")
public class RiskCheckStep implements OrderStep { /* ... */ }
```

冒烟测试增加一条断言：各实体 profile 下管道步骤序列符合预期（步骤名有序列表逐字比对）。管道与 Flowable 构成"轻/重"对照：步骤增删用管道；拓扑含分支、等待、人工任务才上引擎（第七章触发条件）。

#### 5.8.2 模板方法：刻意精简的边界示范

差异点少且永远稳定时最省，但它是继承体系，与组合式策略并存易混淆。只给一个带边界注释的示例：

```java
/**
 * 适用边界：差异点 ≤ 2 且永远不会增长。
 * 一旦差异点会增长（新实体要改中间步骤），必须退化为策略/管道——
 * 继承体系的扩展成本随差异点数量线性恶化。
 */
public abstract class AbstractDocumentGenerator {

    public final byte[] generate(DocumentData data) {   // 固定骨架（final 防子类覆写改流程）
        var doc = newDocument();
        renderHeader(doc, header());                    // 差异点：实体实现 header()
        renderBody(doc, data);                          // 通用
        renderFooter(doc, footer());                    // 差异点：实体实现 footer()
        return toBytes(doc);
    }
    // ↑ 骨架内调用的 newDocument / renderHeader / renderBody / renderFooter / toBytes
    //   均为本类 protected 辅助方法（封装 PDF/HTML 渲染细节，实现省略）；
    //   子类只实现下方两个 abstract 差异点，不接触渲染机制。

    protected abstract HeaderModel header();   // 差异点返回领域内容，渲染逻辑留在骨架

    protected abstract FooterModel footer();
}
```

#### 5.8.3 配置即数据 + Schema 驱动：声明式约束的归处

表单/校验规则/字段映射是最高频的浅层差异。规则存配置（ConfigMap / 数据库），core 只写解释器：

```yaml
# ConfigMap：alpha 的校验规则，改规则不发版
platform:
  validation:
    rules:
      - field: amount
        max: 100000
      - field: currency
        in: [CNY, USD]
```

```java
// 配置绑定：SB4 下用 record + 构造器绑定（呼应 5.0"public 字段宽松绑定已移除"）
@ConfigurationProperties(prefix = "platform.validation")
public record PlatformValidationProperties(List<Rule> rules) {

    public record Rule(String field, Long max, List<String> in) {}
}

@Component
@RequiredArgsConstructor
public class SchemaDrivenValidator {

    private final PlatformValidationProperties rules; // 配置即数据

    public void validate(Order order) {
        // 解释器：无实体判断，规则全来自配置
    }
}
```

关键纪律（与 8.1 第 5 条呼应）：**Schema 只能表达声明式约束**。一旦规则需要分支逻辑（"金额大于 X 且币种为 Y 时……"），立即升级为扩展点——禁止在 Schema 里发明 DSL 表达式语言，那是"字符串配置拼接逻辑"的变体，与 2.2 明确禁止的最差形态同源。

#### 5.8.4 三种模式的分工三角

| 差异类型 | 模式 | 判定 |
| --- | --- | --- |
| 同一件事，算法不同 | 策略 + 注册表 | 接口签名相同，实现替换 |
| 流程步骤增删 | 管道 | 步骤列表不同，单步逻辑可复用 |
| 约束/映射的数值不同 | Schema 驱动 | 能用声明式表达，无分支逻辑 |

三者覆盖 2.1 差异维度中"业务流程 + 数据模型校验"的轻量侧；拿不准归属时默认策略模式——它是三者的安全兜底。

### 5.9 对象健身操（Object Calisthenics）优化

按 ThoughtWorks 对象健身操 9 条军规对 demo 代码体检。结论：骨架整体合规度较高（策略/注册表本身就是军规 2"不用 else"和军规 6"小对象"的产物），但有几处典型违例值得重构——它们同时也是可测试性与演进性的改进点。

#### 军规 4「一行一个点」：OrderService 的链式调用（违例，重构）

```java
// ✗ 违例：policies.pricing().calculate(order) 两个点——
// 应用层穿透了注册表的内部结构（先取策略、再调算法两步被调用方感知）
var price = policies.pricing().calculate(order);
```

```java
// ✓ 重构：把"取策略 + 执行"收敛为注册表的一个完整行为，
// 调用方只说"做什么"，不知道策略的存在
public Money priceFor(Order order) {
    return pricingPolicies.get(EntityContext.current()).calculate(order);
}

// 应用层变为：
var price = policies.priceFor(order);   // 一个点，Demeter 法则合规
```

> 收益不止合规：未来注册表内部机制升级（5.1.1 提到的纯函数化、或第九章的 ServiceLoader 发现），调用方零改动——"一行一个点"本质是封装边界的探针。

#### 军规 3「封装原生类型与字符串」：标识与金额值对象化（违例，重构）

> 落地状态：`OrderId` / `Money` 值对象已落地（`domain/model/`）；下方 Money 示例中的 `stripTrailingZeros` 归一化**已落地**（`Money.java` compact constructor 含 `signum==0 ? BigDecimal.ZERO : amount.stripTrailingZeros()`，signum==0 特判防 `0E-` 形态）。

demo 中 `order.getId()`、`platform.entity` 字符串配置、`Map.of("orderId", ...)` 都是裸值。引入值对象：

```java
public record OrderId(String value) {
    public OrderId {
        Assert.hasText(value, "OrderId 不能为空");
    }
}

public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Assert.notNull(amount, "金额不能为空");
        if (amount.signum() < 0) throw new IllegalArgumentException("金额不能为负");
        // BigDecimal.equals 对 scale 敏感（1.0 ≠ 1.00），record 的 equals/hashCode 直接委托它——
        // 在 compact constructor 中归一化，消除 scale 差异，保证相等性语义与 compareTo 一致
        amount = amount.stripTrailingZeros();
    }
}
```

> 若业务上需要保留 scale 语义（如金额展示精度），则反向约定：**比较一律用 `compareTo`**，并在 8.1 检查清单中登记该约定，禁止在集合去重/Map 键场景依赖 `Money.equals`。两选一，不许混用。

```java
// 端口签名同步升级——编译期就杜绝"把 entity 字符串传进 orderId 参数"
Money calculate(Order order);
String startApproval(Order order);   // 见 7.1：OrderId 由 order.id() 取，entity 由引擎适配层从上下文取
```

> 值对象的校验集中在构造器（compact constructor），消除了散落各处的 `if (amount < 0)`——这也是军规 1/2 的间接收益。`Money` 归 `domain.model`，`EntityType` 已是枚举（合规，不必再包）。
>
> **JPA 映射注意**：值对象进入持久化实体时，record 不能按 JPA 规范直接作 `@Embeddable`（规范要求无参构造 + 可变字段；Hibernate 6+ 对 record embeddable 的支持属实现扩展，不可移植）。可移植写法：为 `OrderId`/`Money` 各配一个 `@AttributeConverter`（如 `AttributeConverter<OrderId, String>`），领域层保持不可变 record，映射细节留在 `infrastructure.persistence`——与 5.1.1 的分层一致。

#### 军规 8「一等集合」：Map 不裸奔（合规但可强化）

`PolicyRegistry` 把 `Map<EntityType, PricingPolicy>` 藏在类内部、只暴露行为方法——已是一等集合的标准形态，**合规**。需要警惕的是反向退化：

```java
// ✗ 禁止：把内部 Map 暴露出去（哪怕 unmodifiable）
public Map<EntityType, PricingPolicy> policies() { ... }

// ✓ 只暴露行为：priceFor(order) / hasPolicy(entity)
```

冒烟测试若需要断言装配数量，通过专门的查询行为（`hasPolicy(type)`，包私有——冒烟测试与被测类放同包路径的 test 源集即可访问）或测试切片注入 `List<PricingPolicy>`，不要让测试需求倒逼封装破坏。

#### 军规 9「不用 getter/setter」：配置类与领域对象（部分违例，重构）

`PlatformProperties` 此前用 Lombok `@Data`（含 setter）——既是军规 9 违例，也与 5.0"SB4 移除 public 字段宽松绑定"冲突。统一改为不可变绑定：

```java
// ✓ record + 构造器绑定：无 setter，配置即不可变事实
@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(EntityType entity) {

    public PlatformProperties {
        Assert.notNull(entity, "platform.entity 必须配置");
    }
}
```

> 绑定细节：record 属性类走构造器绑定（单构造器，无需 `@ConstructorBinding`），通过 `@EnableConfigurationProperties(PlatformProperties.class)` 或 `@ConfigurationPropertiesScan` 注册（不再标注 `@Component`）；compact constructor 的校验发生在**绑定阶段**，配置缺失即启动失败，无需 `@Validated`。

**军规 9 的立场澄清**：它针对的是暴露可变状态的 JavaBean 式 getter/setter；record 访问器（`entity()`、`order.id()`）返回不可变值，属合规——`OrderId`/`Money` 即正例。领域对象同理：`Order` 不暴露 `getPrice()/setPrice()`，用行为方法表达业务语义（`order.priceTo(price)` 已是正确示范，保留）。需要读取的场景优先问"调用方拿这个值去干嘛"——把那个"干嘛"变成 `Order` 的方法。

#### 军规 1/2「单层缩进、不用 else」：Filter 与上下文（合规，说明）

`EntityContextFilter` 的 try/finally 是资源管理惯用法，不算 else 语义；`EntityContext.current()` 用 `Optional.orElseThrow` 替代 if-else——合规。若未来上下文解析变复杂（部署级 + Header 降级 + Token 解析），按军规应拆为职责链而非缩进嵌套：

```java
// 解析策略链，每个解析器一个职责、单层缩进
public interface EntityResolver {
    Optional<EntityType> resolve(HttpServletRequest request);
}
// Filter 中：resolvers.stream().map(r -> r.resolve(req))
//         .flatMap(Optional::stream).findFirst().orElse(defaultEntity);
```

#### 军规 5/6/7：命名、小对象、实例变量（合规）

- 不缩写（`PolicyRegistry` 而非 `PolicyReg`）、类保持单一职责（Filter/Registry/Pipeline 各自一件事）、每类实例变量 ≤2（各 demo 类均满足）——合规，作为持续 review 项保留。

#### 体检结论速查

| 军规 | 状态 | 处置 |
| --- | --- | --- |
| 1 单层缩进 | ✅ | 复杂化时用职责链 |
| 2 不用 else | ✅ | `Optional.orElseThrow` 已示范 |
| 3 封装原生类型 | ⚠️→✅ | 引入 `OrderId`/`Money` 值对象 |
| 4 一行一个点 | ⚠️→✅ | `policies.priceFor(order)` 收敛行为 |
| 5 不缩写 | ✅ | — |
| 6 小对象 | ✅ | — |
| 7 ≤2 实例变量 | ✅ | — |
| 8 一等集合 | ✅ | 禁暴露内部 Map |
| 9 无 getter/setter | ⚠️→✅ | `@Data` → record 构造器绑定 |

> 落地方式：军规 3/4/9 的重构直接替换前文示例（5.2/5.5 的代码以上述版本为准）；8.1 检查清单新增第 12 条：领域对象禁 setter、应用层调用禁链式两点以上、标识与金额必须用值对象——由 ArchUnit（setter 检测）+ review 双重保证。

### 5.10 装配与构建强化

5.2~5.4 给出了最小可用的装配骨架；实体数预期超过 2 个时，以下三项把"新增实体"的成本收敛为"加一个枚举值 + 一个新模块"，核心层零改动（衔接第九章演进第一步）。

#### 5.10.1 统一实体激活机制，消除 @Profile 硬编码

散布各处的 `@Profile("alpha")` 有两个问题：字符串硬编码无编译期约束，且与 `platform.entity` 构成两套开关源（5.3 的唯一事实来源规约靠纪律维持）。收敛为自定义复合注解，底层统一以 `platform.entity` 为唯一开关源：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(name = "platform.entity", havingValue = "alpha")
public @interface ForAlphaEntity {}
```

实体数增长后，进一步抽象为通用条件 + 统一标注：

```java
public class EntityCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // 防御误用：本 Condition 仅服务于 @ForEntity 复合注解，禁止直接 @Conditional(EntityCondition.class)
        // 绕过注解——否则下面 getEnum 会因 missing annotation 抛 NPE，错误信息毫无指向性
        var annotation = metadata.getAnnotations().get(ForEntity.class);
        if (!annotation.isPresent()) {
            return ConditionOutcome.noMatch(
                    "EntityCondition 仅服务于 @ForEntity 复合注解，请改用 @ForEntity 而非直接 @Conditional(EntityCondition.class)");
        }
        var expected = annotation.getEnum("value", EntityType.class);

        // 走 Binder.bind 而非 getProperty + 字符串比对：与 PlatformProperties 的 @ConfigurationProperties
        // 走同一套 relaxed binding（含大小写、连字符容忍），消除两条解析路径的语义漂移（5.3 唯一事实来源）
        var actual = Binder.get(context.getEnvironment())
                .bind("platform.entity", EntityType.class)
                .orElse(null);
        if (actual == null) {
            return ConditionOutcome.noMatch("platform.entity 未配置或无法解析为 EntityType"
                    + "（由 PlatformProperties 启动校验兜底报错）");
        }
        return expected == actual
                ? ConditionOutcome.match("entity=" + actual)
                : ConditionOutcome.noMatch("期望 entity=" + expected + "，实际=" + actual);
    }
}

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(EntityCondition.class)
public @interface ForEntity {

    EntityType value();
}
```

扩展点统一标注 `@ForEntity(EntityType.ALPHA)` 替代裸 `@Profile("alpha")`——注解值与 `supports()` 返回值、`EntityType` 枚举收敛为**同一份编译期事实**（若用裸字符串则与枚举、supports() 构成三份事实，改名/新增实体时三处易漂移），激活语义、失败信息、`supports()` 校验（5.2.5 不变，仍是启动期兜底）全部收口到一处。8.4 契约测试基类相应增加一条"注解声明的实体与 `supports()` 一致"断言（见 8.4）。检查清单 8.1 第 2 条与 8.3 的 ArchUnit 规则同步更新为约束复合注解。

> **设计权衡：为何 `@ForEntity` 与 `supports()` 并存（两份事实）？** 直觉上两者似乎可以合并——让注册表反射读 `@ForEntity` 的 value、从接口去掉 `supports()`。但两者职责不同：`supports()` 是 **SPI 契约**的一部分（注册表、其他策略消费方按签名编程，纯 Java 接口不依赖 Spring）；`@ForEntity` 是 **Spring 装配元数据**（条件评估消费它决定 bean 是否注册）。合并意味着要么接口依赖反射（失去"纯 Java 接口"的测试性与 IoC 容器无关性），要么注册表依赖 Spring 注解（失去容器可替换性）。两份事实的代价是"可能漂移"，由 8.4 契约测试兜底——这是显式的设计选择，不是疏漏。

#### 5.10.2 实体能力自描述（Capability Manifest）

> 落地状态：**已落地**（路线图 P0）。`EntityCapability` 接口在 `platform-core/context`，`AlphaCapability` / `BetaCapability` 在各实体模块 `adapter` 包（`@ForEntity` 限定）；`EntityInfoContributor` 注入 `List<EntityCapability>` 汇总输出到 `/actuator/info`（entity + modules 数组）。当前不实现 `requiredCoreVersion`（文档 8.6 SPI 破坏性变更管理推迟）与 `CapabilityRegistry` 系统性装配校验（`PolicyRegistry` 已对唯一必需扩展点 `PricingPolicy` 做 fail-fast）。

每个实体模块提供一个能力清单 bean，把"这个模块为哪个实体、覆盖哪些扩展点、什么版本"变成可编程查询的事实：

```java
public interface EntityCapability {

    EntityType entity();

    /** 本模块提供的扩展点类型集合（如 PricingPolicy.class；OrderValidator 等其他扩展点见 6.4 / 后续章节） */
    Set<Class<?>> providedPolicies();

    String moduleVersion();
}
```

```java
// entity-alpha
@Component
@ForEntity(EntityType.ALPHA)
public class AlphaCapability implements EntityCapability {

    @Override
    public EntityType entity() { return EntityType.ALPHA; }

    @Override
    public Set<Class<?>> providedPolicies() {
        // OrderValidator 等其他扩展点按工程实际补齐（见 6.4 / 后续章节）
        return Set.of(PricingPolicy.class);
    }

    @Override
    public String moduleVersion() { return "1.4.0"; }
}
```

core 启动时汇总 `List<EntityCapability>`，做三件事：

1. **系统性装配校验**：断言当前实体覆盖全部必需扩展点——比 `PolicyRegistry` 逐个扩展点校验（5.2.5）更系统，缺失即启动失败；
2. **输出 `/actuator/info`**：当前实体、模块版本、能力清单，供 6.3 运行期漂移巡检消费；
3. **生成装配报告**：导出为构建产物，供 CI 比对——某次合并意外改变了某实体的装配面时，报告 diff 直接暴露。

#### 5.10.3 启动类显式扫描边界

默认组件扫描只覆盖启动类所在包，实体模块包路径漂移（重构改名、包层级变动）会导致 bean 静默丢失——装配冒烟（5.7）能兜住，但显式声明扫描边界让意图前置、错误更早：

```java
@SpringBootApplication(scanBasePackages = {"com.example.platform", "com.example.entity"})
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
```

#### 5.10.4 Maven 依赖收敛：两个产物的依赖面必须可控

`-Palpha` 与 `-Pbeta` 两个产物若因传递依赖解析出不同版本的同一库，"同一基线"就是空话。三层手段：

1. **版本锚定**：根 pom 的 `dependencyManagement` 统一锚定全部直接/关键传递依赖版本（实体数增长后可抽 `platform-bom` 模块供独立仓库的实体模块 import）；

2. **Enforcer 收敛规则**（在 8.2 模块边界规则基础上追加）：

```xml
<rules>
    <!-- 同一库在依赖树中只允许解析出一个版本 -->
    <dependencyConvergence/>
    <!-- 传递依赖版本低于声明版本时取高者并要求显式声明 -->
    <requireUpperBoundDeps/>
    <!-- 关键库（协议敏感、序列化敏感）强制全模块同一版本 -->
    <requireSameVersions>
        <dependencies>
            <dependency>com.fasterxml.jackson.core:jackson-databind</dependency>
            <!-- Flowable 8 拆分了 starter（process / idm / eventregistry / cmmn …），不存在裸 flowable-spring-boot-starter；
                 按工程实际引入的模块补齐；CLAUDE.md 提到的 ACT_* 四类 schema（common/process/idm/eventregistry）
                 均需版本统一，否则引擎启动逐一校验会报极具误导性的版本错误 -->
            <dependency>org.flowable:flowable-spring-boot-starter-process</dependency>
        </dependencies>
    </requireSameVersions>
    <bannedDependencies>
        <excludes>
            <exclude>com.example:entity-*</exclude>
        </excludes>
    </bannedDependencies>
</rules>
```

3. **构建期装配报告**：CI 对两个产物分别执行 `mvn dependency:tree`，把两份树 diff 作为构建产物归档——diff 应只含实体模块自身及其专属依赖；出现意外的公共库版本分叉，说明依赖管理被绕过。与 5.10.2 的装配报告互补：装配报告管"bean 面"，依赖树 diff 管"jar 面"。

另加一道**构建期防护**：app 的 alpha/beta 两个 Maven profile 必须互斥——误执行 `mvn -Palpha,beta` 时两个实体模块同时进 classpath，装配冒烟的"同 key 唯一"断言（7.4）能兜住，但构建期直接失败更早更省。落地方式按稳妥度递增三选一：(a) `build.sh alpha|beta` 封装构建脚本入口，禁止直接传多 profile（**推荐**，最简单可靠）；(b) 自定义 Enforcer 规则（实现成本较高，需写 `EnforcerRule` 实现类）；(c) `maven-enforcer-plugin` 自带的 `evaluateBeanshell` 规则求值 `${project.activeProfiles}`——可行但跨 Maven 版本字段形态不稳定，调试成本高，不推荐作为首选。

### 5.11 可观测性深化：三支柱打实体标签

2.5 定了"日志、指标、Trace 都带 entity 标签"的原则，5.2.2 落地了日志（MDC）。指标与 Trace 两支柱补齐如下，与 7.3③ 的流程引擎打标同属 `infrastructure.observation` 的职责。

#### 5.11.1 指标：MeterRegistry 公共标签

```java
@Configuration
public class MetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> entityCommonTags(PlatformProperties props) {
        return registry -> registry.config()
                .commonTags("entity", props.entity().name());
    }
}
```

全部指标自动携带 `entity` 标签，Splunk/Prometheus 告警规则按实体分别配置阈值（2.5），无需在每个埋点手工打标。

#### 5.11.2 Trace：注入 entity attribute

OpenTelemetry 方案——注册 `SpanProcessor`，每个 Span 创建时打实体属性：

```java
@Component
@RequiredArgsConstructor
public class EntitySpanProcessor implements SpanProcessor {

    private final PlatformProperties props;

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        span.setAttribute("entity", props.entity().name());
    }

    @Override
    public boolean isStartRequired() { return true; }

    @Override
    public void onEnd(ReadableSpan span) {}

    @Override
    public boolean isEndRequired() { return false; }
}
```

Micrometer Tracing 方案——用 `ObservationFilter` 在观测层注入低基数 key-value：

```java
@Component
@RequiredArgsConstructor
public class EntityObservationFilter implements ObservationFilter {

    private final PlatformProperties props;

    @Override
    public Observation.Context map(Observation.Context context) {
        return context.addLowCardinalityKeyValue(
                KeyValue.of("entity", props.entity().name()));
    }
}
```

二者按团队 tracing 栈二选一。

#### 5.11.3 日志：MDC 键命名规范与 pattern 统一

日志支柱的实体打标散点（5.2.2 Filter、7.3③ 引擎 Job 线程、8.5 兜底清理）在此汇总为一份规范：

- **MDC 键命名统一**：全平台业务键收敛到三个维度、四个键——`entity`（实体标识，唯一强制）、`orderId`（业务主键，按需）、`processInstanceId` / `activityId`（流程上下文，7.5.2，按需）。禁止各模块自造同义词（`entityType`、`tenant`、`biz`），Splunk 检索与告警模板依赖键名稳定。
- **写入点唯一**：`entity` 只在 `EntityContextFilter`（5.2.2）与异步传播包装器（5.2.3 TaskDecorator、7.3③ Flowable Job 包装）中写入；业务代码只读不写，防覆盖污染。
- **logback pattern 示例**（MDC 键前置，便于按列切分）：

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{ISO8601} %-5level [%thread] entity=%X{entity:-none} traceId=%X{traceId:-} %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
</configuration>
```

`%X{entity:-none}` 的缺省值 `none` 本身是信号：生产日志出现 `entity=none` 且非启动期输出，说明有代码路径绕过了 Filter/传播包装器——可直接做成一条低成本巡检告警。

至此 log（MDC，5.11.3）/ metric（commonTags，5.11.1）/ trace（attribute，5.11.2）三支柱实体维度齐全，排障链路闭环。

---

## 六、数据迁移、契约治理与配置一致性

### 6.1 数据库迁移（Flyway / Liquibase）

- 通用表结构（两个实体库内结构相同、各自一份的表）变更进公共迁移目录，实体专属表/字段进 per-entity 目录：`db/migration/common` + `db/migration/alpha` + `db/migration/beta`，启动时按 `platform.entity` 动态拼接 `locations`：

```yaml
spring:
  flyway:
    locations: classpath:db/migration/common,classpath:db/migration/${platform.entity}
```

```text
src/main/resources/db/migration/
├── common/            # 通用表结构：orders、accounts、ACT_*（7.3②）——各实体库内各自一份
│   ├── V1__init.sql
│   └── V2__orders_add_ext_attributes.sql
├── alpha/             # 实体专属迁移目录，随 platform.entity 拼接到 flyway.locations（6.1）
│   └── V3__alpha_risk_rule_table.sql
└── beta/
    └── V3__beta_audit_config_table.sql
```

> per-entity 目录的版本号序列与 common 独立演进（同版本号在不同实体目录内容不同是允许的）；跨目录引用同一通用对象的变更一律归 common，避免实体间脚本顺序依赖。
- 通用表结构的破坏性变更走 **expand-and-contract**：先 expand（加新列/新表，双写）→ 两个实体都升级完毕 → 后 contract（删旧列）。contract 步骤必须等两套部署版本都越过兼容点，由发布日历协调。
- 回滚策略：expand 阶段永远可回滚；contract 阶段视为不可逆，需评审确认。

### 6.2 契约治理与演进

- **消息契约**：共享 topic 的 schema 用 Schema Registry 管理，兼容性设为 BACKWARD；实体标识（`entity`）必须写入消息头/信封字段，消费端据此设置上下文（见 5.2.3）。
- **API 契约**：两套部署若被同一批下游调用，对外 API 保持同一契约；用消费者驱动契约测试（Spring Cloud Contract / Pact）钉住，核心层每次发版跑全量契约测试。
- **出站凭证按实体隔离**：两个实体调下游系统使用独立的 client_id / 证书 / API Key，禁止共用凭证——一处泄露只影响一个实体，且下游审计日志可按凭证归属区分实体流量（与 2.1"运维/合规"维度一致）。

#### 6.2.1 API 契约演进：分叉分级

实体业务迟早出现"契约必须不同"的时刻。先按变更烈度分级，再决定是否允许分叉：

| 级别 | 变更类型 | 处置 |
| --- | --- | --- |
| 轻 | 响应**新增字段** | 自由演进，下游按 tolerant reader 消费，无需版本号 |
| 中 | 字段**语义变更**（含义、单位、枚举值域变化） | 必须升版本号（`/v2/...`），新旧版本并行一个迁移窗口 |
| 重 | 端点或字段**下线** | expand-and-contract：先标记 `Deprecation` 与 `Sunset` 响应头 → 确认**两个实体的下游都越过兼容点** → 才允许删除。下线决策以调用方遥测为准，不以"应该没人用了"为准 |

#### 6.2.2 必须分叉时：端点级分叉

当某实体确需对外暴露差异化 API 时，**优先端点级分叉**——在实体模块内用 `@RestController` + `@ForEntity(EntityType.ALPHA)` 注册专属端点（如 `/alpha/v1/risk-report`），随装配激活，核心层零感知：

- **禁止同名端点不同语义**（两个实体对 `/v1/orders/{id}` 返回不同结构）——这是契约治理的最差形态，进 8.1 硬规则；8.3 的 ArchUnit 增加断言：core 的 `interfaces.rest` 包不出现任何实体路径前缀（`/alpha/`、`/beta/`），实体模块不出现与 core 重复的 `@RequestMapping` 路径；
- 契约测试按两层组织：**公共契约**（core 维护，两实体部署都必须通过）+ **实体增量契约**（实体模块维护，只约束本实体的专属端点），CI 装配矩阵（5.7）中各自执行；
- 分叉端点同样打 `entity` 标签并纳入 5.11 可观测性，监控上与普通端点无差别。

### 6.3 配置治理

三个开关成对：构建 profile ↔ `SPRING_PROFILES_ACTIVE` ↔ `platform.entity`，漂移则启动失败。`@ForEntity`（5.10.1 已落地）**直接**以 `platform.entity` 为激活开关源——`SPRING_PROFILES_ACTIVE` 通过激活 `application-${platform.entity}.yaml` **间接**设置 `platform.entity`（6.3.1 配置分层，yaml 中 `platform.entity: alpha`）；构建 profile（Maven `-Palpha`）决定哪些实体模块的类进入 classpath。三者构成"构建期裁剪 → 配置层提供实体标识 → 运行期激活"的链路，任一环不一致即 PolicyRegistry fail-fast（5.2.5）。防线：

1. 启动期：`PolicyRegistry` 构造器校验（5.2.5），任一漂移即启动失败；
2. CI 期：装配冒烟矩阵 + 负例测试（5.7）；
3. 运行期：`/actuator/info` 输出当前实体与构建版本（由 5.10.2 的 Capability Manifest 供给），接入监控巡检，发现"实体 A 的命名空间里跑着实体 B 的镜像"立即告警。

#### 6.3.1 配置分层叠加与重复键防护

配置按三层叠加，职责严格分层：

```text
application.yaml                      # 公共层：两实体共享的默认值（进平台仓库）
    ↓ 覆盖
application-${platform.entity}.yaml   # 实体层：本实体专属差异（随实体模块/实体配置仓库）
    ↓ 覆盖
环境 ConfigMap                        # 环境层：dev/staging/prod 的连接串、资源配额
```

- **实体层禁止重复定义公共层已有的键**——重复定义是漂移的温床（改公共层时忘记同步实体层）。CI 用 `yq` 做键集合 diff：`yq '.. | path | join(".")' application-alpha.yaml` 与公共层键集求交集，非空即构建失败；环境层允许覆盖公共键（那是它的职责）。
- 每层只答一个问题：公共层答"大家都一样的是什么"，实体层答"这个实体不同的是什么"，环境层答"这个环境不同的是什么"。答错层的配置在 review 中打回。

#### 6.3.2 密级分级与密钥治理

| 密级 | 内容 | 存放 |
| --- | --- | --- |
| 非密 | 阈值、开关、映射表、校验规则 | ConfigMap / Git |
| 密钥 | DB 口令、下游凭证、证书私钥 | **一律** Vault / External Secrets / Sealed Secrets，禁止明文进 Git 与实体模块代码库（8.1 硬规则） |

- 密钥按**实体独立命名空间、独立轮转**：alpha 与 beta 的 DB 口令、下游 client_id 各自独立（与 6.2 出站凭证隔离呼应），一次泄露只影响一个实体，轮转互不需要协调窗口。
- Sealed Secrets 的加密实体清单可进 Git（GitOps 友好）；Vault 路径约定 `secret/{entity}/{env}/...`，路径即权限边界。

#### 6.3.3 "改配置不发版"的纪律与配置回滚

- 5.8.3 的 Schema 规则、业务开关等"改配置不发版"的内容**必须进 Git（GitOps）**：带版本号、走 PR 审批，禁止直接改集群 ConfigMap——手工热改是审计黑洞，也是两套部署漂移的最大来源。
- `/actuator/info` 除输出构建版本外，同时输出**配置版本号**（Git commit short hash），纳入 6.3 的运行期巡检："镜像版本新、配置版本旧"与反向组合都触发告警。
- 配置回滚 = `git revert` + 重新下发，与代码回滚（6.5）走同一条流水线，不搞第二套回滚通道。

#### 6.3.4 Actuator 端点安全

`/actuator/info` 承载巡检事实，其余端点按最小暴露管理：

```yaml
management:
  server:
    port: 9090                     # 独立管理端口，与业务端口分离
  endpoints:
    web:
      exposure:
        include: info,health,metrics,prometheus   # 只暴露巡检所需四项
```

独立管理端口上再叠 K8s NetworkPolicy：只允许监控系统（Prometheus/Splunk）命名空间访问 9090，业务流量路径完全摸不到 actuator。严禁暴露 `env`/`heapdump`/`shutdown`——`env` 端点会泄露配置中的敏感值，与 6.3.2 的密钥分级直接冲突。

### 6.4 扩展字段：结构通用、校验差异

数据模型维度的差异（2.1）最轻量的落地形态：各实体库内的 `orders` 表承载通用结构，实体专属字段进 `ext_attributes` JSONB 扩展列，**结构差异不发版、校验差异走 SPI**——示范"结构通用、校验差异"的分离。

```sql
-- common 目录：各实体库内的 orders 表统一加扩展属性列（严格分库，各自执行）
ALTER TABLE orders ADD COLUMN ext_attributes JSONB NOT NULL DEFAULT '{}';
```

```java
/** 扩展字段校验是实体差异，收敛为扩展点（与 PricingPolicy 同款 SPI 套路） */
public interface OrderValidator {

    EntityType supports();

    /** 校验 extAttributes 中本实体专属字段的结构与取值，违规抛 ValidationException */
    void validateExtAttributes(Map<String, Object> extAttributes);
}
```

```java
// entity-alpha：alpha 要求 ext_attributes 必含 riskLevel 且枚举受限
@Component
@ForEntity(EntityType.ALPHA)
public class AlphaOrderValidator implements OrderValidator {

    @Override
    public EntityType supports() { return EntityType.ALPHA; }

    @Override
    public void validateExtAttributes(Map<String, Object> extAttributes) {
        // Alpha 专属校验逻辑
    }
}
```

三条纪律：

1. **通用字段进列、差异字段进 JSONB**：被核心层查询/索引/关联的字段必须是真实列；JSONB 只承载实体私有、无跨实体语义的字段，防止通用表结构被单一实体的字段污染（呼应第四章"通用表结构大量单实体字段"警告信号）。
2. **校验走 SPI 而非 Schema 表达式的场景**：JSONB 内部字段若只是数值/枚举约束，优先用 5.8.3 的 Schema 驱动；涉及分支逻辑才升级为 `OrderValidator` 扩展点——分工判定同 5.8.4 三角。
3. **JSONB 内容无 Flyway 约束**：字段级演进靠校验器版本化 + 实体专属迁移目录里的数据订正脚本，别指望数据库 DDL 兜底。

### 6.5 发布编排、灰度与回滚

两套部署独立发布不等于"随便发"。变更按影响面分三类定序，发布单必须注明类别（8.1 第 15 条）：

| 类别 | 定义 | 定序与回滚 |
| --- | --- | --- |
| ① 纯实体变更 | 只动实体模块代码 + 该实体库的迁移脚本 | 单实体独立发布/灰度/回滚，另一侧完全无感 |
| ② 内核兼容变更 | core 变更但 SPI/API/schema 向后兼容 | 灰度顺序无强制要求（不像 ③ 类要 expand 先行双侧协调）；但**两个产物都必须重建并跑过装配冒烟**（5.7 矩阵）后再发布——兼容变更也可能改变装配面 |
| ③ 内核破坏性变更 | SPI 签名、共享 API/schema、路由键的破坏性变更（8.6） | expand 先行双侧（新结构两个实体都可用）→ 双侧都越过兼容点 → contract；**回滚只允许发生在 expand 阶段**，contract 后视为不可逆（与 6.1 迁移回滚策略同源） |

**灰度单位是实体，不是 Pod。** 同一实体内的滚动发布是部署细节；跨实体的灰度顺序才是发布策略——变更先全量发到 beta（或流量较小的一侧），观察 `entity` 维度告警（5.11 指标标签正是为此存在）至少一个业务周期，确认无回归再发 alpha。回滚同理：只回滚出问题的一侧。

**代码回滚 × Flyway 阶段可回滚性矩阵**（严格分库下每侧独立适用）：

| 迁移所处阶段 | 代码回滚 | 数据回滚 | 结论 |
| --- | --- | --- | --- |
| 仅 expand（新列/新表已建，旧列仍写） | ✅ 直接回滚旧版本（旧代码不认识新列，无害） | 不需要 | 安全窗口 |
| expand 双写中 | ✅ 回滚后旧代码只写旧列，新列停更 | 不需要 | 安全窗口 |
| contract 已执行（旧列已删） | ❌ 旧代码引用已删列，启动即炸 | 只能备份恢复 | 不可逆，发布前评审 |

纪律：③类变更的 contract 脚本**单独成 PR、单独发版**，不与功能变更混发；发布日历（6.1）标注每个实体的兼容点越过时间。

---

## 七、Flowable 流程引擎集成

> 触发条件：两个实体的差异主要在**流程拓扑**（审批层级、状态机、分支条件），且拓扑变化频率高于代码发版频率，或业务方需要可视化评审流程。
> 反例：差异只是"某步算钱逻辑不同"→ 策略模式足够；流程固定不变 → 模板方法/管道更轻。引擎不是必选项。

**版本硬性约束：Spring Boot 4 必须使用 Flowable ≥ 8.0。** Flowable 7.x（含 7.2）基于 Spring Framework 6 / Boot 3，在 SB4 下存在已知不兼容（自动配置类缺失等）；Flowable 8.0 起才迁移到 Spring Framework 7 / Boot 4 / Jackson 3 基线（同时 8.0 不再支持 Boot 3）。版本选择纳入 6.3 的依赖锚定（Enforcer 依赖规则，如 `bannedDependencies` / `requireUpperBoundDeps`），并与 5.0 的迁移检查项联动。若采用 Flowable 8.0 早期版本，需显式评估其生产成熟度。

### 7.1 Flowable 的角色：流程拓扑差异的外置容器

| 层 | 归属 | 内容 |
| --- | --- | --- |
| 流程拓扑（先后次序、分支条件、审批链路） | **BPMN 文件，随实体模块部署** | A 实体三级审批、B 实体五级审批 → 两份流程定义，不进代码 |
| 任务实现（干活逻辑） | 代码，按差异归属分流 | 通用任务进 `platform-core`；实体专属任务进 `entity-alpha`/`entity-beta`，与 `PricingPolicy` 相同的 SPI 套路 |
| 引擎本身（运行时、持久化、历史、事务集成） | **共享内核 `platform-core`** | 引擎配置、表结构、与 Spring 事务/数据源的整合，不允许分叉 |

代码从"流程编排者"退化为"任务实现者"。`OrderService` 里没有 `if (entity == A) { 走三步审批 } else { 走五步 }`，只有：

```java
@Service
@RequiredArgsConstructor
public class OrderApprovalService {

    private final RuntimeService runtimeService;

    @Transactional
    public String startApproval(Order order) {
        // 流程变量只放轻量标识，不放实体对象（避免序列化与历史表膨胀）；
        // delegate 内按 orderId 重新加载领域对象
        var orderId = order.id(); // OrderId 值对象（军规 3）
        return runtimeService.startProcessInstanceByKey(
                        "order-approval",
                        orderId.value(),
                        Map.of("orderId", orderId.value(),
                               "entity", EntityContext.current().name()))
                .getProcessInstanceId();
    }
}
```

关键纪律：**共享内核只依赖 Flowable 引擎 API，不依赖任何具体流程定义；流程定义 key（`order-approval`）构成内核与实体模块之间的契约**，与 SPI 接口同级，纳入契约治理。

### 7.2 多实体应对：部署级隔离（与现有架构天然契合）

两个实体本来就是独立部署 + 独立数据库，因此 **每套部署一个独立的 ProcessEngine 实例、各自一套 Flowable 表（ACT_*），天然物理隔离**：

```text
实体 A 部署                实体 B 部署
├── 同一代码基线构建        ├── 同一代码基线构建
├── classpath 含 alpha BPMN ├── classpath 含 beta BPMN
├── 独立 Flowable 表        ├── 独立 Flowable 表
└── 独立流程实例            └── 独立流程实例
```

落地要点：

1. **BPMN 放实体模块资源目录**：`entity-alpha/src/main/resources/processes/order-approval.bpmn20.xml`，随 Maven profile 裁剪进入对应产物（复用 5.4 机制），引擎启动自动部署。A 实体的流程定义不进入 B 的镜像。**前提**：Flowable starter 默认自动部署 `classpath*:/processes/**/*.bpmn20.xml`——若裁剪失效导致两个实体模块同时进入 classpath，同 key 两份定义会以 v1/v2 共存，`startProcessInstanceByKey` **静默路由到最新版本，不报错只错路由**，比启动失败更危险。因此 5.7 负例必须增加"同一 key 部署定义数 == 1"的启动期断言（见 7.4）；同时注意 `classpath*:` 会扫描所有依赖 jar，防止依赖传递带入第三方示例 BPMN。
2. **流程定义 key 统一、拓扑各异**：两份 BPMN 的 `processDefinitionKey` 都是 `order-approval`，内部结构不同。内核按 key 发起实例，不关心是哪个实体的拓扑——与策略注册表"按 key 路由"同构。本项目严格分库，每套部署独立数据源，隔离天然成立；仅作一般性说明——若共享物理库，必须显式配置 `flowable.database-schema` 区分 schema。
3. **任务实现走条件装配**：
  - 通用任务（如 `SendNotificationDelegate`）进 core；
  - 专属任务（如 `AlphaRiskCheckDelegate`）进实体模块，加 `@Profile("alpha")`。BPMN 中用委托表达式引用：`flowable:delegateExpression="${alphaRiskCheckDelegate}"`。

```java
@Component("alphaRiskCheckDelegate")
@Profile("alpha")
public class AlphaRiskCheckDelegate implements JavaDelegate {

    @Override
    public void execute(DelegateExecution execution) {
        // Alpha 专属风控任务
    }
}
```

4. **不使用 Flowable 的 tenantId 多租户模式**：Flowable 原生支持按 `TENANT_ID_` 在同一引擎内隔离流程定义与实例，适用于"单部署多租户 SaaS"。本方案已按部署物理隔离，再引入 tenantId 是重复机制，徒增每张表的查询与运维复杂度；且 tenantId 方案会迫使核心层感知"按租户部署哪份流程定义"，把拓扑差异泄进 core，违反 8.1 第 1 条的边界规则。**只有当未来演进为单部署混部多实体时才切换 tenantId**（见第九章）。
5. **事务边界：每套部署内业务 schema 与 ACT_* 表必须同库、同一 DataSource、同一事务管理器**——`@Transactional` + `startProcessInstanceByKey` 的原子性依赖此前提。若确需分离，只能引入 outbox/最终一致并写入检查清单。两个已知坑：(a) delegate 抛异常会回滚整个业务事务，delegate 内的外部副作用（调下游、发消息）需自行权衡幂等；(b) async 续跑节点在原事务外执行，不能假设与发起方事务一致。

### 7.3 三个必须特别处理的问题

**① 版本漂移与在途流程实例**

BPMN 变更比代码变更更危险：新流程定义部署后，**已在运行中的旧实例仍按旧拓扑执行**（Flowable 默认行为，且应保留）。纪律：

- 流程定义变更视同 schema 变更，走 expand-and-contract：新增节点/分支安全；删除或重命名节点前必须确认无在途实例——**通过公共 API 查询**（`runtimeService.createProcessInstanceQuery()` / `createExecutionQuery()`），不要直查 `ACT_RU_EXECUTION` 表（表结构语义跨版本不稳定，且破坏引擎封装边界）；
- 两套部署存在版本差时，流程相关领域事件（如"审批完成"消息）契约必须向后兼容，纳入 6.2 契约治理；
- 确需迁移在途实例时，用迁移 Builder（Flowable 6.4+）：`runtimeService.createProcessInstanceMigrationBuilder()`，先 `validateMigration(processInstanceId)` 校验、再 `migrate(processInstanceId)` 执行，必要时用 `addActivityMigrationMapping(...)` 做活动映射；批量场景用 `ProcessMigrationService`。先在测试环境演练。

**② 引擎表与业务迁移的统一治理**

per-entity 的 Flyway 治理（6.1）覆盖两部分：

- 业务表：`common + alpha/beta` 目录，如 6.1 所述；
- Flowable 表：统一走 6.1 的 `common` 目录——**引擎升级属于核心层变更，对两个实体都有兼容性义务**，两套部署需协调升级窗口。两个配套硬性要求：
  1. 必须设置 `flowable.database-schema-update=false`，否则引擎启动期自行 DDL，与 Flyway 管理产生漂移冲突；
  2. Flowable 官方只发布 Liquibase changelog，**Flyway 脚本需团队自维护**——每次升级 Flowable 版本 = 人工核对 ACT_* 表结构差异并补齐 Flyway 脚本，此项列入 8.1 检查清单，属于持续运维成本，引入引擎前应知晓。

**③ 可观测性**

- 日志/指标继续打 `entity` 标签；Flowable 的同步 delegate 运行在 Web 请求线程内，MDC 天然带上；但 **async 节点与 Job 执行器（AsyncExecutor）运行在引擎自有线程池**，5.2.3 的 `TaskDecorator` 不适用（那是 Spring `@Async` 的扩展点）。落地方式（放 core）：为 Flowable 的 SpringAsyncExecutor 提供自定义 `TaskExecutor`，包装 Runnable 复制 MDC/`entity` 上下文；或注册引擎事件监听器/`ProcessEngineLifecycleListener` 在 Job 入口打标。流程变量中显式携带的 `entity`（见 7.1 代码）作为双保险。
- 按实体维度监控两个关键指标：活跃流程实例数、**死信 Job 数（deadletter job）**——后者是流程引擎最该告警的指标，非零即需人工介入。

### 7.4 冒烟测试扩展

装配冒烟矩阵（5.7）增加两条自研校验。**注意：Flowable 引擎本身不做启动期 delegate 校验**——`delegateExpression` 在活动执行时才求值，缺 bean 时流程运行到该任务才抛 `FlowableObjectNotFoundException`，是在途实例级事故。因此以下校验需自研实现（`ApplicationRunner` 或并入 5.7 冒烟），与 5.2.5 PolicyRegistry 的 fail-fast 同等级：

```java
@SpringBootTest
@ActiveProfiles("alpha")
class AlphaProcessAssemblySmokeTest {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private ApplicationContext context;

    @Test
    void 同一流程key只有一份部署定义() {
        // 防双 BPMN 静默错路由：裁剪失效时同 key 会出 v1/v2 共存
        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("order-approval").list())
                .hasSize(1);
    }

    @Test
    void EventRegistry通道与事件定义同key唯一() {
        // 与 BPMN 同款风险：裁剪失效时两实体的 .channel/.event 同时进 classpath，
        // 会导致入站事件重复消费/错配。断言 EventRegistryRepositoryService 中定义唯一。
    }

    @Test
    void 所有BPMN引用的delegate均已装配() {
        repositoryService.createProcessDefinitionQuery().list().forEach(definition -> {
            var model = repositoryService.getBpmnModel(definition.getId());
            // 遍历 model 中所有 ServiceTask / TaskListener，
            // 提取 delegateExpression="${xxx}" 的 bean 名，
            // 断言 context.containsBean("xxx")
        });
    }
}
```

### 7.5 外部集成通道（HTTP / Spring Event / MQ）

流程与外部系统的集成有三条通道，设计纪律如下（模式源于 Flowable 7.x 实战，**Flowable 8 下代码细节需重新验证**，设计原则不变）。

#### 7.5.1 通道选型

| 模式 | 通道 | 适用 |
| --- | --- | --- |
| 同步编排 | HTTP service task（delegate 内调下游） | 请求-响应、实时、短耗时（支付、发货） |
| 异步编排 | MQ + message/event catch | 长耗时、解耦、可重试（审批、风控回调） |
| 事件驱动触发 | MQ/Event Registry → start/correlate | 外部系统触发流程 |
| 事件发射 | service task → Spring Event / MQ（AFTER_COMMIT / Outbox） | 流程里程碑通知外部 |

混合使用：同一流程内同步步骤走 HTTP，异步步骤走 message catch + MQ。

#### 7.5.2 HTTP 集成纪律

- **错误分类**：4xx → 抛 `BpmnError`（业务错误，走 error boundary event 进入补偿/人工分支）；5xx / 超时 → 抛技术异常，配合 service task 的 `failedJobRetryTimeCycle`（如 `R3/PT5S`）由 Job 重试。注意 `failedJobRetryTimeCycle` 生效需 `flowable:async="true"` + async executor 已开启，二者配套缺一不可。
- **超时与熔断**：HTTP client 必须配 connect/read timeout；下游加 Resilience4j 熔断。
- **幂等**：Job 会重试，下游调用必须幂等（idempotency-key / 业务键去重）。
- **delegate 线程安全（最高频 bug 源）**：`delegateExpression` 引用的 delegate 是 Spring 单例，被多个 Job 线程并发调用——**禁止在 delegate 字段中保存任何执行态**（execution、请求/响应对象），操作注册表必须无状态。
- Flowable 原生 HTTP Task（`flowable:type="http"`）可作浅层集成的次选；鉴权、报文组装等行为差异仍走实体模块 delegate（与 2.1"外部集成"维度一致）。
- **trace 贯通**：下游调用透传 `X-Trace-Id`，并把 `processInstanceId`、`activityId` 与 `entity` 一并写入 MDC——Splunk 可按流程实例 + 实体双维度检索。

#### 7.5.3 Spring Event 桥接纪律

- 流程 → 事件（里程碑通知）：delegate 内 `publisher.publishEvent(...)`；**有副作用的监听器一律 `@TransactionalEventListener(AFTER_COMMIT)`**——默认同步 `@EventListener` 在引擎事务内执行，监听器抛异常会回滚整个流程推进，监听器的外部副作用（通知、缓存）则无法随回滚撤销。AFTER_COMMIT 方向的两个坑同样须知：(a) 监听器异常发生在事务已提交之后，**无法回滚流程推进**，失败必须自带重试/告警；(b) 监听器内若写库，原事务已结束，需自带 `@Transactional(REQUIRES_NEW)`。
- 事件 → 流程（外部事件推进等待中的流程）：按 **businessKey**（不用 executionId——流程版本升级/异步边界后易变）查询订阅并 `messageEventReceived`；存在循环/多实例时 `singleResult()` 会抛异常，必须用 `list()` 遍历。该监听器默认在发布者事务内执行，流程推进失败会拖垮上游业务事务，需用 `@TransactionalEventListener` 或 `REQUIRES_NEW` 隔离。
- Spring Event 只是应用内通知，**不替代 BPMN message/signal**（后者有持久化订阅，重启不丢）。

#### 7.5.4 MQ 集成纪律

- **生产侧禁止在 delegate 里直接 `kafkaTemplate.send()`**：发送成功而引擎事务随后回滚时，消息无法撤回，消费者会看到"不存在"的流程状态。可靠做法二选一：① Transactional Outbox——delegate 同事务写 outbox 表，独立 relay 投递（至少一次 + 消费端幂等）；② Spring Event + `AFTER_COMMIT` 转发 MQ。
- **Flowable Event Registry（推荐，声明式零消费代码）**：`.event` / `.channel` 定义文件放 `classpath*:/eventregistry/` 自动部署，把 Kafka topic 与 BPMN message catch 声明式桥接，outbound 通道在引擎内部处理与流程事务的衔接（**上线前用回滚用例实测确认**：发消息后强制回滚，断言消息未发出——开源版边界行为曾有差异）。**多实体适配**：与 BPMN 同款处理——通道/事件定义放实体模块随 profile 裁剪，event key 跨实体统一、关联参数（correlation，如 orderId）一致。
- **未匹配事件必须接监控**：Event Registry 中未被任何订阅匹配的事件默认丢弃，生产环境需接 non-matching event 处理接口转死信/告警，否则"消息被吞"无感知。
- 消费侧：幂等（业务键去重）；MQ 死信（消费失败）与 BPMN 死信（deadletter job）分开监控；一体化告警 = consumer lag + 入站失败率 + deadletter job 数 + entity 维度。

### 7.6 优雅停机与 Flowable 协调

Pod 终止时，引擎 AsyncExecutor 上正在跑的 async Job（服务任务续跑、定时器触发）若被硬杀，会留下在途实例不一致或依赖 Job 重试恢复——放大多实体场景的风险：两套部署升级窗口错开时，一方停机正是另一方流量高峰。

```yaml
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s   # 每个优雅停机阶段的等待上限，给在途 Job 完成窗口
```

K8s 部署清单配套 preStop 缓冲，让端点摘除先于停机开始，避免停机期间新流量打入：

```yaml
lifecycle:
  preStop:
    exec:
      command: ["sh", "-c", "sleep 10"]   # 等待 endpoints 摘除与负载均衡收敛
```

要点：

1. **停机顺序**：preStop sleep（摘流量）→ SIGTERM → Spring 优雅停机（拒绝新请求、等待在途请求）→ Flowable AsyncExecutor 关闭（`timeout-per-shutdown-phase` 覆盖此阶段，在途 Job 完成或超时中断靠重试恢复）。
2. **可重试是兜底而非借口**：async 节点的 delegate 必须幂等（7.5.2 同纪律），超时中断的 Job 由 `failedJobRetryTimeCycle` 恢复，停机窗口内允许出现短暂重试、不允许出现死信激增——发布后巡检 deadletter job 指标（7.3③）。
3. **多实体升级窗口**：两套部署的滚动发布日历错开编排，任一侧发布时通过 `entity` 维度告警（5.11）单独盯该实体，避免"一锅端"式联合回归。

---

## 八、Review 检查清单与工程护栏

### 8.1 硬性规则（可落入团队编码标准）

1. `EntityType` / `platform.entity` 可见性矩阵（与 5.1.1 六边形分层对齐）：**允许**——`context`（定义）、`application.port`（注册表/管道解析）、`interfaces.filter`（边缘识别）、`infrastructure`（装配与打标）、`domain.port`（`supports()` 声明适配实体，属端口契约）；**禁止**——`domain.model/service/event`（ArchUnit 强制）、`application` 中除 `application.port` 外的用例编排代码（出现即打回）。
2. 扩展点接口的实现类必须声明 `supports()` 且被实体激活注解限定：统一使用 `@ForEntity` 复合注解（5.10.1，底层以 `platform.entity` 为唯一开关源）；禁止再散落裸 `@Profile` / `@ConditionalOnProperty` 硬编码形成双轨。
3. 实体模块之间零相互依赖，core 不依赖任何实体模块（Maven Enforcer 强制）。
4. 日志/指标必须带 `entity` 标签（ArchUnit 只能约束代码结构，MDC 属运行时行为——靠统一 Filter/切面 + 日志评审保证）。
5. 新增差异时先问：配置能表达吗？能 → 禁止写成 `@Value` + if 判断；不能 → 新扩展点。
6. 异步代码路径（`@Async`、消息消费）必须经 `TaskDecorator`/消息头传播上下文，PR 中含 `@Async` 必查传播。
7. `EntityContext` 仅限同步 Servlet 栈；引入 WebFlux 依赖需架构评审。
8. 引入流程引擎后：核心层禁止出现具体流程定义 key 之外的 BPMN 解析逻辑；流程定义变更走 expand-and-contract，删除/重命名节点前必须用公共 API 核查在途实例；BPMN 引用的 delegate bean、流程/事件/通道定义的同 key 唯一性必须有冒烟测试覆盖。
9. 流程引擎运维纪律：`flowable.database-schema-update=false`（表结构归 Flyway 管）；业务表与 ACT_* 同库同事务管理器；每次升级 Flowable 版本须人工核对 ACT_* 差异并补 Flyway 脚本。
10. delegate 纪律：单例无状态（字段禁存执行态）；禁止 delegate 内直接发 MQ（走 Outbox / AFTER_COMMIT）；HTTP 调用必须配超时且下游幂等。
11. 事件纪律：有副作用的监听器必须 `@TransactionalEventListener(AFTER_COMMIT)`；流程关联一律用 businessKey；Event Registry 未匹配事件必须接监控。
12. 领域对象禁 setter、应用层调用禁链式两点以上、标识与金额必须用值对象（5.9 军规 3/4/9）——setter 检测由 ArchUnit 强制，链式调用与值对象化靠 review；`Money` 比较约定（stripTrailingZeros 归一化或一律 compareTo，5.9）二选一登记在案。
13. API 契约纪律：**禁止同名端点不同语义**；必须分叉时走端点级分叉（实体模块注册专属端点，6.2.2），core 的 `interfaces.rest` 包不出现实体路径前缀（ArchUnit 断言，8.3）；下线变更必须先 `Deprecation`/`Sunset` 头并确认两实体下游都越过兼容点。
14. 定时任务纪律：PR 含 `@Scheduled` 必须注明任务作用域（per-entity / global，5.2.6）；global 任务必须带选主机制（ShedLock，严格分库下锁存储用 Redis 或 K8s Lease）。
15. 发布纪律：发布单模板必填"本次变更属 6.5 的 ①/②/③ 哪类"；③类变更的 contract 脚本单独成 PR、单独发版；灰度以实体为单位，先发一侧观察 entity 维度告警再发另一侧。
16. 安全纪律：实体模块与 Git 仓库**零明文密钥**（一律 Vault / External Secrets / Sealed Secrets，6.3.2）；Actuator 独立管理端口 + 仅暴露 info/health/metrics/prometheus + NetworkPolicy 限制（6.3.4）；下游出站凭证按实体隔离（6.2）。

### 8.2 Maven Enforcer 示例

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-enforcer-plugin</artifactId>
    <executions>
        <execution>
            <id>enforce-module-boundaries</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
                <rules>
                    <bannedDependencies>
                        <excludes>
                            <!-- platform-core 中禁止出现任何实体模块 -->
                            <exclude>com.example:entity-*</exclude>
                        </excludes>
                    </bannedDependencies>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 8.3 ArchUnit 示例

**ArchUnit 只能看到当前模块测试 classpath 上的类。** 若把跨实体规则放在 platform-core 里 `@AnalyzeClasses(packages = "com.example.platform")`，实体包（`com.example.entity.*`）根本不在 classpath，"实体间零依赖""必须限定激活注解"两条规则**永远不触发且静默通过**——护栏形同虚设。因此按可见性拆分两处：

- **core 内分层规则**留在 `platform-core` 测试源集（只分析 core 包即可，见 5.1.1 onionArchitecture 规则）；
- **跨实体规则**挪到 `app` 模块测试源集——app 是唯一同时依赖 core 与实体模块的地方（组合根，5.1.1）：

```java
// app 模块测试源集：跨实体边界规则
@AnalyzeClasses(packages = "com.example",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGuardTest {

    // 自检：防静默失效——断言实体包确实被导入，否则下列规则全是空转
    @ArchTest
    static void 实体包必须被导入(JavaClasses classes) {
        // 按 CLAUDE.md「app 测试按 assembly.entity 门控」——beta 装配跑时只有 entity-beta 包在
        // classpath，写死 "alpha" 会让 beta 测试自检失败。从系统属性取当前装配实体动态拼包名前缀。
        var entity = System.getProperty("assembly.entity");
        assertThat(classes)
                .as("当前装配实体 (%s) 的包必须被导入，否则下列规则全是空转", entity)
                .anyMatch(c -> c.getPackageName().startsWith("com.example.entity." + entity));
    }

    @ArchTest
    static final ArchRule 扩展点实现必须限定实体激活注解 =
            classes()
                    .that().implement(PricingPolicy.class)
                    .should().beMetaAnnotatedWith(Conditional.class); // @ForEntity 的元注解（5.10.1）

    @ArchTest
    static final ArchRule 实体模块之间零依赖 =
            noClasses().that().resideInAPackage("..entity.alpha..")
                    .should().dependOnClassesThat().resideInAPackage("..entity.beta..")
                    .andShould(noClasses().that().resideInAPackage("..entity.beta..")
                            .should().dependOnClassesThat().resideInAPackage("..entity.alpha.."));
}
```

> `实体模块之间零依赖` 这条规则正是"entity-alpha 包不依赖 entity-beta 包"的包级表达：Enforcer `bannedDependencies`（8.2）在 **jar/模块坐标粒度** 拦截，ArchUnit 在**包/类粒度**拦截——后者更细，能抓住同模块内跨实体包的代码引用（如重构把 beta 类拖进了 alpha 包），两者配套而非替代。实体数增长后，该规则可用 `slices().matching("..entity.(*)..")` + `namingSlices` 的循环切片断言参数化，避免每加一个实体改规则。

### 8.4 扩展点契约测试基类

> 落地状态：抽象基类 `PricingPolicyContractTest` 已在 platform-core test 源集，由 `maven-jar-plugin` 的 `test-jar` goal 发布；`AlphaPricingPolicyContractTest` / `BetaPricingPolicyContractTest` 已继承落地。当前覆盖两条防漂移契约（`supports()` 与 `@ForEntity` value 一致）；通用计算契约（非负、空扩展属性容忍）依赖测试夹具（`Orders.minimalValid()` 等），待补 fixture 后扩展。

装配冒烟（5.7）验证"装配对不对"，契约测试验证"实现符不符合扩展点契约"。core 提供抽象基类，实体模块继承即获得契约回归，**新增实体契约测试零编写**——这是"内核可演进"在测试侧的闭环，与 2.6 测试策略对应：

```java
// platform-core 测试源集：扩展点契约
public abstract class PricingPolicyContractTest {

    /** 由实体模块提供被测实现 */
    protected abstract PricingPolicy policy();

    /** 由实体模块声明自身适配的实体 */
    protected abstract EntityType expectedEntity();

    @Test
    void 计算结果非负() {
        var order = Orders.minimalValid(); // core 提供的测试夹具
        assertThat(policy().calculate(order).amount()).isNotNegative();
    }

    @Test
    void supports与所在模块实体一致() {
        assertThat(policy().supports()).isEqualTo(expectedEntity());
    }

    @Test
    void 激活注解声明的实体与supports一致() {
        // @ForEntity 的枚举值与 supports() 是同一份事实（5.10.1），防两处漂移
        var annotation = policy().getClass().getAnnotation(ForEntity.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(policy().supports());
    }

    @Test
    void 空扩展属性不抛异常() {
        var order = Orders.withoutExtAttributes();
        assertThatCode(() -> policy().calculate(order)).doesNotThrowAnyException();
    }
}
```

```java
// entity-alpha 测试源集：继承即回归，一行实现都不用改契约
class AlphaPricingPolicyContractTest extends PricingPolicyContractTest {

    @Override
    protected PricingPolicy policy() { return new AlphaPricingPolicy(); }

    @Override
    protected EntityType expectedEntity() { return EntityType.ALPHA; }
}
```

要点：

1. 契约用例只写**对所有实现成立的命题**（非负、幂等、null 容忍），实体特有行为留在实体自己的单测里——基类膨胀成"按实体分支断言"就违背了初衷。
2. 每新增一个扩展点（`OrderValidator`、`OrderStep`）配一个契约基类，与 5.10.2 能力清单的 `providedPolicies()` 呼应：装配校验管"有没有"，契约测试管"对不对"。
3. CI 装配矩阵（5.7）中契约测试随实体模块 `mvn verify` 自然执行，无需新增流水线阶段。

### 8.5 EntityContext 泄漏防护兜底

5.2.2 的 Filter 用 try/finally 清上下文，但工程上总有"忘记 finally"的路径（自定义拦截器、手工提交线程池、WebSocket 握手后逻辑）；Tomcat 线程池复用下，一次漏清 = 后续请求全部串实体。在 Filter 之外加两道兜底，宁可重复清理、不可放任污染：

```java
@Component
public class EntityContextCleanupInterceptor implements HandlerInterceptor {

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 兜底：无论 Filter 是否已清理，请求结束即归位（MDC.remove/clear 幂等，重复调用安全）
        MDC.remove("entity");
        EntityContext.clear();
    }
}
```

```java
@Bean
public ThreadPoolTaskExecutor orderTaskExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    // ... 常规配置
    executor.setTaskDecorator(runnable -> () -> {
        try {
            runnable.run();
        } finally {
            // 线程池兜底：任务结束无论上下文是否被设置过，统一清理后归还线程
            MDC.clear();
            EntityContext.clear();
        }
    });
    return executor;
}
```

> 兜底只防泄漏、不替代传播——`@Async` 场景仍需 5.2.3 的 `TaskDecorator` 先传播再兜底清理（装饰器可组合，先包传播、外层包清理）。检查清单 8.1 第 6 条的 PR 审查点相应升级为：含 `@Async`/线程池的 PR 必查"传播 + 兜底清理"成对出现。

### 8.6 SPI 破坏性变更管理

`domain.port` 的扩展点接口（`PricingPolicy`、`OrderStep`、`OrderValidator`……）是内核与实体模块之间的二进制契约。内核与实体模块虽然同库构建、同版本发布，但实体数增长、走向独立发版（第九章第三步）后，破坏性变更的代价从"编译报错"升级为"运行期 `NoSuchMethodError`"——治理手段要提前就位：

1. **CI 二进制兼容检查**：用 japicmp（或 revapi）对 SPI 包与上一发布版做比对，破坏性变更直接构建失败：

```xml
<!-- platform-core 构建配置：SPI 包与前版比对，break 即失败 -->
<plugin>
    <groupId>com.github.siom79.japicmp</groupId>
    <artifactId>japicmp-maven-plugin</artifactId>
    <configuration>
        <oldVersion><version>${last.release.version}</version></oldVersion>
        <parameter>
            <onlyModified>true</onlyModified>
            <includes><include>com.example.platform.domain.port</include></includes>
            <breakBuildOnBinaryIncompatibleModifications>true</breakBuildOnBinaryIncompatibleModifications>
        </parameter>
    </configuration></plugin>
```

2. **接口演进三步走，禁止一步到位改签名**：新增需求 → 先加**默认方法**（`default` 实现委托旧方法或抛 `UnsupportedOperationException`）→ 旧方法标 `@Deprecated` 并给一个完整大版本的迁移窗口 → 下下个大版本才删除。 japicmp 报"方法删除"即触发评审：是否已走完三步。
3. **`supports()` 路由键的增删视为契约变更**：新增 `EntityType` 枚举值 = 所有扩展点实现都要补新实现，按"内核破坏性变更"走 6.5 第③类发布编排；删除枚举值需先确认对应实体模块已下线。
4. **Capability Manifest 携带内核版本约束**：5.10.2 的 `EntityCapability` 增加 `requiredCoreVersion()` 字段，core 启动汇总时校验——实体模块声明的最低内核版本高于当前内核版本即 fail-fast 启动失败，防止"新实体模块配旧内核"的半升级态：

```java
public interface EntityCapability {

    EntityType entity();

    Set<Class<?>> providedPolicies();

    String moduleVersion();

    /** 本模块编译所针对的最低内核版本，启动期与内核实际版本比对 */
    String requiredCoreVersion();
}
```

---

## 九、演进方向

1. **流程拓扑差异变大** → 把审批/状态迁移外置到 Camunda / Flowable，每个实体部署自己的 BPMN 流程定义，代码只剩任务实现。
2. **结构性差异（数据模型）大到扩展点兜不住** → 按健康度标准拆成两个服务，只共享平台层（安全、审计、消息、监控等横切能力）。
3. **实体数量增长（>3）** → 从 `@Profile` 平滑迁移到插件发现机制：
  - 第一步：注册表键从枚举改为 `String`，新增实体不改核心层；
  - 第二步：扩展点定义收敛为独立 `platform-spi` 模块，语义化版本管理；
  - 第三步：实体模块独立仓库、独立发版，运行期通过 ServiceLoader / 插件目录发现，内核只对稳定的 SPI 版本契约负责。

### 9.1 应用层上下文：从 ThreadLocal 演进到 ScopedValue（JDK 25+）

**原则**：`EntityContext` 基于 ThreadLocal，在"边缘设置一次、同步栈内读取"的模型下工作良好；但当应用层内部需要用虚拟线程做结构化并发（批量拆分子任务并行处理）时，ThreadLocal 不继承、手工传播又容易漏——ScopedValue（JEP 506，JDK 25 转正）为这类场景提供了不可变、按作用域绑定的更优解。

**前提与边界（先立纪律，防止误用）**：

- JDK 基线目前为 21：Scoped Values 在 JDK 21~24 均为预览（生产需 `--enable-preview`，不建议），**基线升级 JDK 25 前维持 ThreadLocal 现状不动**。
- ScopedValue 只对**作用域内新建的子线程**生效——`Thread.ofVirtual().start()` 与 `StructuredTaskScope` 继承绑定；
- **parallelStream / ForkJoinPool commonPool 不继承**（JEP 明确设计如此），commonPool 工作线程里读 ScopedValue 会抛异常——并行流场景禁用；
- `@Async` 线程池、MQ 监听器等**预先存在的池线程**不继承绑定，跨池传播仍需 5.2.3 的 TaskDecorator——是分工，不是替代。

**正确示例（StructuredTaskScope，JDK 25 正式 API）**：

```java
public final class EntityScopedContext {

    public static final ScopedValue<EntityType> CURRENT = ScopedValue.newInstance();

    private EntityScopedContext() {}

    public static EntityType current() {
        return CURRENT.orElseThrow(() -> new IllegalStateException("EntityContext 未初始化"));
    }
}

// 应用服务内：作用域内新建的虚拟线程自动继承绑定
public List<Order> processBatch(List<CreateOrderCommand> cmds, EntityType entity) throws Exception {
    return ScopedValue.callWhere(EntityScopedContext.CURRENT, entity, () -> {
        try (var scope = StructuredTaskScope.open()) {
            var tasks = cmds.stream()
                    .<StructuredTaskScope.Subtask<Order>>map(cmd -> scope.fork(() -> {
                        var order = Order.from(cmd);
                        return order.withPrice(policies.priceFor(order)); // current() 安全
                    }))
                    .toList();
            scope.join();
            return tasks.stream().map(StructuredTaskScope.Subtask::get).toList();
        }
    });
}
```

> `callWhere`/`runWhere` 为 JDK 23+ 引入的静态便捷方法，JDK 25 正式可用；JDK 21 预览 API 是 `ScopedValue.where(k, v).run(...)`，写法不同勿混用。`StructuredTaskScope` 在 JDK 25 经 JEP 525 转正，`StructuredTaskScope.open()` 为正式入口。

**分工结论**：三种机制并存，各管一段——

| 机制 | 位置 | 覆盖场景 |
| --- | --- | --- |
| 边缘 Filter 的 ThreadLocal | 5.2.2 | 同步 Servlet 请求栈，一次路由 |
| TaskDecorator / 消息头传播 | 5.2.3 | 预先存在的池线程（@Async、MQ 监听） |
| ScopedValue | 9.1 | 应用层内部新建虚拟线程的结构化并发 |

### 9.2 Spring Modulith：事件治理与测试切片（边界仍以 Maven + ArchUnit 为主）

**定位纠偏（先写清，防止误读）**：Modulith 管理的是启动类包下的**逻辑包模块**，不替代 Maven 物理模块边界（8.2 Enforcer）与跨实体 ArchUnit 规则（8.3）。引入它只为两件事：**模块事件治理**与**模块测试切片**。Spring Boot 4 对应 Spring Modulith 2.0（按 [Spring 官方兼容矩阵](https://docs.spring.io/spring-modulith/reference/appendix.html)：Modulith 2.0 编译针对 Spring Boot 4.0；Modulith 1.x 对应 Spring Boot 3.x。撰写时 Modulith 2.0 仍处 SNAPSHOT/milestone 阶段，正式版发布前需关注官方里程碑）。

**用途一：`@ApplicationModuleListener` 解决 7.5.3 的事务陷阱**：

```java
@Component
class AlphaRiskEventListener {

    @ApplicationModuleListener // = @Async + REQUIRES_NEW 新事务 + AFTER_COMMIT
    void onOrderCreated(OrderCreatedEvent event) {
        // 监听器异常不回滚上游订单事务
    }
}
```

纪律：其可靠性依赖 Event Publication Registry（事件发表落库 + 失败重投），引入即新增一张事件表与配套基础设施——默认 JDBC 实现，建表脚本进 core 的 common Flyway 目录（6.1）；不需要重投语义的轻量事件仍用普通 `@TransactionalEventListener`（7.5.3 纪律不变）。

**用途二：`@ApplicationModuleTest` 模块切片加速 CI**：

```java
@ApplicationModuleTest
@ActiveProfiles("alpha")
class AlphaModuleSmokeTest { /* 只加载 alpha 模块切片，不起全平台 */ }
```

切片按包模块工作，与 5.7 的装配冒烟（验证 profile×entity 装配矩阵）**互补不替代**——装配负例（5.7 ApplicationContextRunner）不变。

**附赠**：`new Documenter(modules).writeModulesAsPlantUml()` 可输出模块依赖图，挂进 CI 制品即得一份随代码漂移自动更新的模块视图。

### 9.3 AOT / GraalVM 原生镜像兼容性预留

**真实风险**：Spring AOT 在构建期冻结条件评估，`@ForEntity` 底层 `@Conditional` 读 `platform.entity`（5.10.1）；AOT 处理时属性缺失，则实体 Bean 被构建期排除——运行期才发现"策略注册表为空"。

**正解（零代码改动）**：按实体构建时把 `platform.entity` 注入 AOT 处理阶段——

```bash
mvn spring-boot:process-aot -Palpha -Dspring.aot.properties.platform.entity=alpha
# 或构建环境注入 PLATFORM_ENTITY=alpha（ relaxed binding ）
```

条件在构建期正常求值，产物即为该实体的冻结装配——这与"一实体一镜像"的部署模型（5.4/6.5）天然一致：每个实体各跑一次 AOT 处理，各出各的镜像。

**CI 校验 Job（5.7 装配矩阵扩展一格）**：`mvn spring-boot:process-aot -Palpha` / `-Pbeta` 跑通 AOT 处理阶段即可，不必出 native 镜像；发现不支持的条件/动态代理直接 fail，把 AOT 不兼容挡在决定出 Native 镜像之前。

**反模式警示**：不要为每个实体手写 `BeanFactoryInitializationAotProcessor` 注册单例绕过条件装配——双份维护，且破坏 5.10.1 的单一开关源原则。

**已知限制**：Flowable、ShedLock 等第三方库的 native 支持度需在选型时按 GraalVM reachability metadata 逐一确认，作为触发该演进时的前置检查项。

### 落地路线图（优先级）

前文各强化项按投入产出排序，作为演进实施顺序。"触发条件/信号"列与第四章健康度警告信号呼应——信号出现即把对应行提前：

| 优先级 | 项目 | 位置 | 理由 | 触发条件/信号 |
| --- | --- | --- | --- | --- |
| P0 | ArchUnit 护栏失效修复（规则挪 app 模块 + 自检测试） | 8.3 | 缺陷修复：扫描范围不含实体包时规则静默空转，护栏不存在 | 发现规则"从未失败过"即最高优先 |
| ~~P0~~ 已落地 | PolicyRegistry 重复实现显式报错 | 5.2.5 | 缺陷修复：Duplicate key 无上下文，排查成本高 | 已落地（PolicyRegistry 自定义 merge 函数抛带实体名/实现类名的 IllegalStateException） |
| ~~P0~~ 已落地 | 负例测试可断言化 | 5.7 | 缺陷修复：@SpringBootTest 无法断言启动失败 | 已落地（MisconfiguredAssemblyTest 用等价的 AnnotationConfigApplicationContext + assertThatThrownBy(context::refresh)） |
| P0 | 运行时三处小缺陷（MVC 异步上下文 / ~~Money scale~~✅已落地 / ~~@ForEntity 枚举化~~✅已落地） | 5.2.2 / 5.9 / 5.10.1 | 缺陷修复：均为低频高损型 bug 源 | 异步端点出现、金额比较出工单；@ForEntity 枚举化 + Money scale 已落地，仅余 MVC 异步上下文 |
| ~~P0~~ 已落地 | 实体能力自描述（Capability Manifest） | 5.10.2 | 直接闭环"装配正确性"与 6.3 漂移检测 | 已落地（EntityCapability 接口 + Alpha/BetaCapability + EntityInfoContributor 汇总到 /actuator/info） |
| ~~P0~~ 已落地 | Flyway locations 按实体动态拼接 | 6.1 | 闭环 6.1 的 per-entity 迁移目录原则 | 已落地（application.yaml: `locations: classpath:db/migration/common,classpath:db/migration/${platform.entity}`） |
| ~~P0~~ 已落地 | Micrometer 指标打 entity 标签 | 5.11.1 | 闭环 2.5 可观测性原则，一处配置全局生效 | 已落地（MetricsConfig.entityCommonTags + micrometer-registry-prometheus + exposure 加 metrics,prometheus） |
| ~~P1~~ 已落地 | 统一实体激活机制（@ForEntity） | 5.10.1 | 消除 @Profile 硬编码双轨，落地于本次重构 | 已随 H2 修复落地 |
| ~~P1~~ 已落地 | 扩展点契约测试基类（含注解与 supports 一致断言） | 8.4 | 新增实体契约回归零编写 | 已随 H2 修复落地（test-jar 基础设施一并补齐） |
| P1 | EntityContext 泄漏防护兜底 | 8.5 | 防线程池串实体 | 异步路径（@Async/消息消费）增多 |
| P1 | API 契约演进与分叉管理 | 6.2.1/6.2.2 | 下游集成复杂度增长前先立规矩 | 首个实体专属端点需求 |
| P1 | SPI 破坏性变更管理（japicmp + requiredCoreVersion） | 8.6 | 内核/实体走向独立发版前必须就位 | 实体模块独立仓库或独立发版提上日程 |
| P1 | 发布编排、灰度与回滚分类定序 | 6.5 | 首个 ③ 类破坏性变更前必须有发布纪律 | 首次 SPI/schema 破坏性变更排期 |
| P1 | per-entity 配置治理（分层 diff / 密钥分级 / GitOps） | 6.3 | 配置漂移是双部署最高频事故源 | 首次"改配置没发版却两侧行为不一致"工单 |
| P1 | Maven 依赖收敛 + 依赖树 diff | 5.10.4 | 防两产物依赖面静默分叉 | 依赖冲突/版本漂移工单出现 |
| P1 | @Scheduled 双部署语义 + ShedLock 选主 | 5.2.6 | global 任务双跑是资损/重复执行风险 | 首个 global 定时任务需求 |
| P1 | 本地开发体验（compose 双实例 + Testcontainers 冒烟） | 5.7.1 | 新成员上手成本、CI 无 DB 依赖 | 新人入职环境搭建超过半天 |
| P2 | 日志支柱规范（MDC 键命名 + pattern 统一） | 5.11.3 | 汇总既有散点为规范，成本低 | 检索时发现同义键/键缺失 |
| P2 | 测试策略索引化 | 2.6 | 文档导航，零成本 | 测试落地章节定位困难时 |
| P2 | 扩展字段（JSONB + SPI 校验） | 6.4 | 数据模型差异场景触发后补 | 首个实体专属字段需求 |
| P2 | Trace 注入 entity attribute | 5.11.2 | 接入 tracing 栈后补 | tracing 栈选型落地 |
| P2 | ArchUnit 实体包边界规则参数化（slices） | 8.3 | 实体模块代码量增长后补 | 实体数 >2 |
| P2 | 优雅停机与 Flowable 协调 | 7.6 | 多实体升级窗口错开场景触发后补 | 首次发布窗口冲突/停机期死信告警 |
| P2 | ScopedValue 替代应用层 ThreadLocal | 9.1 | 消除结构化并发下的手工上下文传播 | JDK 基线升级 25 且出现结构化并发批量处理场景 |
| P2 | Spring Modulith 事件治理与测试切片 | 9.2 | 事件事务陷阱一次性治理 + CI 提速 | 跨模块事件事务工单频发或 CI 矩阵超阈值 |
| P2 | AOT 兼容性（构建期注入 platform.entity + CI smoke） | 9.3 | 零代码改动预留 Native 能力 | 决定出 Native 镜像（Serverless/极速扩容）时 |