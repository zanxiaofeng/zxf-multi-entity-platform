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
- [七、Review 检查清单与工程护栏](#七review-检查清单与工程护栏)
- [八、演进方向](#八演进方向)

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
| 业务流程 | 审批链路不同、状态机不同 | 策略模式 + 工作流引擎（Camunda / Flowable） |
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
3. **管道/过滤器（Pipeline）**：流程步骤的增删差异（A 多一步风控，B 多一步审计）用管道编排，优于 if-else 嵌入主流程。
4. **模板方法（Template Method）**：主流程骨架固定、个别步骤不同。适合差异点少且稳定的业务。
5. **特性开关（Feature Toggle）**：仅用于发布控制和临时差异，不要用它承载长期业务差异——开关数量指数增长，测试矩阵爆炸。开关必须有 TTL、责任人和到期报警约定，逾期不清理即技术债工单。
6. **流程引擎外置（Camunda / Flowable）**：差异主要在流程拓扑时，把流程定义外置为 BPMN，每个实体部署自己的流程定义文件，代码只剩任务实现。
7. **配置即数据 + Schema 驱动**：表单、校验规则、字段映射用 Schema 描述存库，代码只写解释器。适合差异高频但浅层的场景。

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

- **JDK 基线 17**（非 21）；21+ 用于虚拟线程（`spring.threads.virtual.enabled=true`），但虚拟线程下 ThreadLocal 语义不变，上下文传播问题依然存在（见 5.2.4）。
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
        EntityContext.set(properties.getEntity());
        MDC.put("entity", properties.getEntity().name()); // 日志带实体维度，Splunk 可按 entity 分别告警
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
@Data
@ConfigurationProperties(prefix = "platform")
public class PlatformProperties {

    /** 当前部署服务的实体（唯一事实来源） */
    private EntityType entity;
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
        if (!pricingPolicies.containsKey(properties.getEntity())) {
            throw new IllegalStateException(
                    "当前实体 %s 未装配 PricingPolicy，请检查 SPRING_PROFILES_ACTIVE 与 platform.entity 是否一致"
                            .formatted(properties.getEntity()));
        }
    }

    public PricingPolicy pricing() {
        return pricingPolicies.get(EntityContext.current());
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
        var order = Order.from(cmd);                     // 通用：结构
        var price = policies.pricing().calculate(order); // 差异：委托扩展点
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
        assertThat(registry.pricing().supports()).isEqualTo(EntityType.ALPHA);
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

## 七、Review 检查清单与工程护栏

### 7.1 硬性规则（可落入团队编码标准）

1. `platform-core` 中全文检索 `EntityType` / `platform.entity`，只允许出现在注册表、上下文、Filter 中，业务服务里出现即打回。
2. 扩展点接口的实现类必须声明 `supports()` 且被 `@Profile` 限定；禁止 `@Profile` 与 `@ConditionalOnProperty` 双轨混用。
3. 实体模块之间零相互依赖，core 不依赖任何实体模块（Maven Enforcer 强制）。
4. 日志/指标必须带 `entity` 标签（ArchUnit 或切面强制）。
5. 新增差异时先问：配置能表达吗？能 → 禁止写成 `@Value` + if 判断；不能 → 新扩展点。
6. 异步代码路径（`@Async`、消息消费）必须经 `TaskDecorator`/消息头传播上下文，PR 中含 `@Async` 必查传播。
7. `EntityContext` 仅限同步 Servlet 栈；引入 WebFlux 依赖需架构评审。

### 7.2 Maven Enforcer 示例

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

### 7.3 ArchUnit 示例

```java
@AnalyzeClasses(packages = "com.example.platform")
class ArchitectureGuardTest {

    @ArchTest
    static final ArchRule 核心层不得感知实体枚举 =
            noClasses()
                    .that().resideInAPackage("..core.service..")
                    .should().dependOnClassesThat().areAssignableTo(EntityType.class);

    @ArchTest
    static final ArchRule 扩展点实现必须限定Profile =
            classes()
                    .that().implement(PricingPolicy.class)
                    .should().beAnnotatedWith(Profile.class);
}
```

---

## 八、演进方向

1. **流程拓扑差异变大** → 把审批/状态迁移外置到 Camunda / Flowable，每个实体部署自己的 BPMN 流程定义，代码只剩任务实现。
2. **结构性差异（数据模型）大到扩展点兜不住** → 按健康度标准拆成两个服务，只共享平台层（安全、审计、消息、监控等横切能力）。
3. **实体数量增长（>3）** → 从 `@Profile` 平滑迁移到插件发现机制：
   - 第一步：注册表键从枚举改为 `String`，新增实体不改核心层；
   - 第二步：扩展点定义收敛为独立 `platform-spi` 模块，语义化版本管理；
   - 第三步：实体模块独立仓库、独立发版，运行期通过 ServiceLoader / 插件目录发现，内核只对稳定的 SPI 版本契约负责。
