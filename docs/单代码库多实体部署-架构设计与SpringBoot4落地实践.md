# 单代码库多实体部署：架构设计与 Spring Boot 4 落地实践

> 适用场景：同一套代码基线，各自独立部署，为两种相似业务的实体提供服务（产品变体架构）。
> 目标：差异被隔离、内核可演进、维护成本不随实体数量线性增长。

---

## 目录

- [一、问题本质与核心原则](#一问题本质与核心原则)
- [二、架构设计层面的关键注意点](#二架构设计层面的关键注意点)
- [三、可用的架构与设计模式](#三可用的架构与设计模式)
- [四、健康度判断标准](#四健康度判断标准)
- [五、Spring Boot 4 落地骨架（Demo）](#五spring-boot-4-落地骨架demo)
- [六、数据迁移、契约治理与配置一致性](#六数据迁移契约治理与配置一致性)
- [七、Flowable 流程引擎集成](#七flowable-流程引擎集成)
- [八、Review 检查清单与工程护栏](#八review-检查清单与工程护栏)
- [九、演进方向](#九演进方向)

---

## 一、问题本质与核心原则

这个问题本质是**产品变体（Product Variant）架构**：同一份代码基线，通过部署形态和差异化配置服务两类业务实体。做得好是平台化，做不好就是 if-else 地狱和双重维护。

核心原则：**差异越早（越靠近边缘）被识别和路由，核心代码越干净。**

最怕的情况是差异渗透到领域模型深处——每个聚合、每个服务里都有 `if (entityType == "A")`，此时共库的收益已经被维护成本抵消。

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
- 涉及结构的差异（数据模型、流程拓扑）→ 谨慎评估是否真该共库。结构性差异太大时，强行共用代码库的维护成本高于"拆成两个仓库、共享平台层"。

### 2.3 明确共享内核（Shared Kernel）的边界

一套代码库要成立，必须划出不允许分叉的核心层：

- 领域模型中真正通用的概念（订单、账户、文档这类）
- 横切能力：安全、审计、幂等、事务、消息、PDF 生成、监控埋点
- 基础设施适配层

核心层的演进要按平台的标准治理：语义化版本、变更评审、对两套部署都有兼容性义务。**差异代码只允许出现在扩展点实现里。**

### 2.4 运行时隔离与部署形态

各自部署天然获得进程级隔离，注意三点：

- **产物同源、按需裁剪**：同一代码库产出，但按实体裁剪打包（见 5.4）。注意：按 Maven profile 裁剪后打出的 jar 内容不同，**是两个不同的二进制产物**，而非"同一 artifact"——CI 必须对每个产物分别构建与冒烟，不能拿其中一个产物的测试结果给另一个背书。只有在合规/镜像瘦身无要求时，才考虑"全量打包 + 运行期按配置激活"的单产物模式。
- **数据库**：默认推荐分库（schema-per-entity），让两个实体可以独立升级 schema、独立备份恢复、独立扩容。共库只在数据量小且强关联时考虑。
- **版本漂移**：两套部署允许存在版本差吗？如果允许，共享的消息契约、API 契约、数据库 schema 变更必须前后兼容（expand-and-contract 模式，见第六章）。

### 2.5 可观测性必须打实体维度

所有日志、指标、Trace 都带上 `entity` 标签。两套部署共用一套监控体系（如 Splunk）时，没有这个维度等于无法排障；告警规则也应按实体维度分别配置阈值。

### 2.6 测试策略

- 共享内核：一套契约测试 + 单元测试。
- 每个实体：针对其扩展点实现的集成测试（Testcontainers 起真实依赖）。
- CI 上跑装配矩阵：**每个构建产物 × 对应 profile**，防止某个实体的配置把另一个实体的装配搞坏——这是共库部署最常见的线上事故来源。同时要覆盖**负例**：profile 与 `platform.entity` 不一致时启动必须失败（见 5.7）。

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
    - 共享表里有大量仅一个实体使用的字段；
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
5. **实体模块的定位升级**：`entity-alpha` 从"SPI 实现包"升级为"**该实体的适配器包**"——它不仅能实现定价端口，还可以提供专属持久化适配器（实体专属表的 Repository 实现）、专属外部系统适配器（2.1"外部集成"维度的归处），全部通过 `@Profile` 装配插到 core 端口上。
6. **既有代码的归位**：`OrderService`（5.5）→ `application.OrderApplicationService`；`EntityContextFilter` → `interfaces.filter`；`PolicyRegistry` / `OrderPipeline` → `application.port`（含 Spring 装配的端口解析机制）；`OrderRepository` 接口 → `domain.port`，JPA 实现 → `infrastructure.persistence`。5.2 节的示例代码按此包归属理解（示例中省略 package 声明）。

> 注意 DDD 战略层面的一条边界：两个实体**共享同一个限界上下文**（共库方案成立的前提）。当健康度检查（第四章）触警、实体拆分为独立服务时，拆分线就是新的限界上下文线——此时共享内核降级为共享平台层（第九章），端口/适配器结构使拆分的物理成本最小：适配器模块原样搬走。但端口契约**不会自动变成**跨服务 API 契约——端口只是拆分的**接缝候选**，拆分时需在其上新增 DTO、版本化、幂等与容错层（进程内接口是同步、共享事务、共享领域对象的；跨服务契约这些假设全部断裂，见 6.2 契约治理）。

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
        this.pricingPolicies = policies.stream()
                .collect(Collectors.toUnmodifiableMap(PricingPolicy::supports, Function.identity()));
        // 启动期防护：当前实体必须有且仅有一个实现装配，否则直接启动失败
        if (!pricingPolicies.containsKey(properties.entity())) {
            throw new IllegalStateException(
                    "当前实体 %s 未装配 PricingPolicy，请检查 SPRING_PROFILES_ACTIVE 与 platform.entity 是否一致"
                            .formatted(properties.entity()));
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

> **扩展点实现类不允许调用对方模块的任何类**——Maven 依赖边界从构建期保证这一点（见 7.2 Enforcer 配置）。

**实体身份的唯一事实来源规约**：`platform.entity` 是唯一配置项；扩展点用 `@Profile("alpha")` 激活时，必须保证部署清单中 `SPRING_PROFILES_ACTIVE=alpha` 与 `platform.entity=alpha` 成对出现（启动期由 5.2.5 的校验兜底）。Spring profile 只用于环境（dev/staging/prod）与实体激活，禁止再引入第三个开关源（如 `@ConditionalOnProperty` 与 `@Profile` 混用），否则双轨漂移即事故。

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
// 负例：profile 与 platform.entity 漂移时启动必须失败
@SpringBootTest(properties = "platform.entity=beta")
@ActiveProfiles("alpha")
class MisconfiguredAssemblyTest {

    @Test
    void profile与entity不一致时上下文启动失败() {
        // 断言 ApplicationContext 启动抛 IllegalStateException
        // 可用 ApplicationContextRunner 或 assertThatThrownBy 包裹 SpringApplication.run
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

    public final byte[] generate(DocumentData data) {   // 固定骨架（辅助方法体省略）
        var doc = newDocument();
        renderHeader(doc, header());                    // 差异点：实体实现
        renderBody(doc, data);                          // 通用
        renderFooter(doc, footer());                    // 差异点：实体实现
        return toBytes(doc);
    }

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
    }
}
```

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

---

## 六、数据迁移、契约治理与配置一致性

### 6.1 数据库迁移（Flyway / Liquibase）

- 共享表变更进公共迁移目录，实体专属表/字段进 per-entity 目录：`db/migration/common` + `db/migration/alpha` + `db/migration/beta`，启动时按 `platform.entity` 组合 `locations`。
- 共享表的破坏性变更走 **expand-and-contract**：先 expand（加新列/新表，双写）→ 两个实体都升级完毕 → 后 contract（删旧列）。contract 步骤必须等两套部署版本都越过兼容点，由发布日历协调。
- 回滚策略：expand 阶段永远可回滚；contract 阶段视为不可逆，需评审确认。

### 6.2 契约治理

- **消息契约**：共享 topic 的 schema 用 Schema Registry 管理，兼容性设为 BACKWARD；实体标识（`entity`）必须写入消息头/信封字段，消费端据此设置上下文（见 5.2.3）。
- **API 契约**：两套部署若被同一批下游调用，对外 API 保持同一契约；用消费者驱动契约测试（Spring Cloud Contract / Pact）钉住，核心层每次发版跑全量契约测试。

### 6.3 配置一致性与漂移检测

三个开关必须一致：`platform.entity` ↔ `SPRING_PROFILES_ACTIVE` ↔ 构建产物（Maven profile）。防线：

1. 启动期：`PolicyRegistry` 构造器校验（5.2.5），任一漂移即启动失败；
2. CI 期：装配冒烟矩阵 + 负例测试（5.7）；
3. 运行期：`/actuator/info` 输出当前实体与构建版本，接入监控巡检，发现"实体 A 的命名空间里跑着实体 B 的镜像"立即告警。

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
2. **流程定义 key 统一、拓扑各异**：两份 BPMN 的 `processDefinitionKey` 都是 `order-approval`，内部结构不同。内核按 key 发起实例，不关心是哪个实体的拓扑——与策略注册表"按 key 路由"同构。隔离成立的前提是**每套部署独立数据源**；若共享物理库，必须显式配置 `flowable.database-schema` 区分 schema。
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

---

## 八、Review 检查清单与工程护栏

### 8.1 硬性规则（可落入团队编码标准）

1. `EntityType` / `platform.entity` 可见性矩阵（与 5.1.1 六边形分层对齐）：**允许**——`context`（定义）、`application.port`（注册表/管道解析）、`interfaces.filter`（边缘识别）、`infrastructure`（装配与打标）、`domain.port`（`supports()` 声明适配实体，属端口契约）；**禁止**——`domain.model/service/event`（ArchUnit 强制）、`application` 中除 `application.port` 外的用例编排代码（出现即打回）。
2. 扩展点接口的实现类必须声明 `supports()` 且被 `@Profile` 限定；禁止 `@Profile` 与 `@ConditionalOnProperty` 双轨混用。
3. 实体模块之间零相互依赖，core 不依赖任何实体模块（Maven Enforcer 强制）。
4. 日志/指标必须带 `entity` 标签（ArchUnit 只能约束代码结构，MDC 属运行时行为——靠统一 Filter/切面 + 日志评审保证）。
5. 新增差异时先问：配置能表达吗？能 → 禁止写成 `@Value` + if 判断；不能 → 新扩展点。
6. 异步代码路径（`@Async`、消息消费）必须经 `TaskDecorator`/消息头传播上下文，PR 中含 `@Async` 必查传播。
7. `EntityContext` 仅限同步 Servlet 栈；引入 WebFlux 依赖需架构评审。
8. 引入流程引擎后：核心层禁止出现具体流程定义 key 之外的 BPMN 解析逻辑；流程定义变更走 expand-and-contract，删除/重命名节点前必须用公共 API 核查在途实例；BPMN 引用的 delegate bean、流程/事件/通道定义的同 key 唯一性必须有冒烟测试覆盖。
9. 流程引擎运维纪律：`flowable.database-schema-update=false`（表结构归 Flyway 管）；业务表与 ACT_* 同库同事务管理器；每次升级 Flowable 版本须人工核对 ACT_* 差异并补 Flyway 脚本。
10. delegate 纪律：单例无状态（字段禁存执行态）；禁止 delegate 内直接发 MQ（走 Outbox / AFTER_COMMIT）；HTTP 调用必须配超时且下游幂等。
11. 事件纪律：有副作用的监听器必须 `@TransactionalEventListener(AFTER_COMMIT)`；流程关联一律用 businessKey；Event Registry 未匹配事件必须接监控。
12. 领域对象禁 setter、应用层调用禁链式两点以上、标识与金额必须用值对象（5.9 军规 3/4/9）——setter 检测由 ArchUnit 强制，链式调用与值对象化靠 review。

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

```java
@AnalyzeClasses(packages = "com.example.platform")
class ArchitectureGuardTest {

    // 完整六边形分层规则见 5.1.1（onionArchitecture + 领域/应用层零实体感知）
    // 以下保留两条与分层正交的专项规则：

    @ArchTest
    static final ArchRule 扩展点实现必须限定Profile =
            classes()
                    .that().implement(PricingPolicy.class)
                    .should().beAnnotatedWith(Profile.class);

    @ArchTest
    static final ArchRule 实体模块之间零依赖 =
            noClasses().that().resideInAPackage("..entity.alpha..")
                    .should().dependOnClassesThat().resideInAPackage("..entity.beta..")
                    .andShould(noClasses().that().resideInAPackage("..entity.beta..")
                            .should().dependOnClassesThat().resideInAPackage("..entity.alpha.."));
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