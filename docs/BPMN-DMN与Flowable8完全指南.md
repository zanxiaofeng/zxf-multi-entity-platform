# BPMN 2.0 / DMN 与 Flowable 8 完全指南

> 适用版本：BPMN 2.0 标准 + Flowable 8.0.x
> 基线要求：Java 17+、Spring Framework 7、Spring Boot 4

---

## 目录

- 第一部分：BPMN 2.0 节点类型详解
  - 1. 事件（Event）
  - 2. 活动（Activity）
  - 3. 网关（Gateway）
  - 4. 连接对象与泳道
  - 5. 典型组合模式
  - 6. 元素速查表
- 第二部分：Flowable 对 BPMN 元素的支持矩阵
  - 7. 事件支持
  - 8. 活动支持
  - 9. 网关与连接支持
  - 10. Flowable 扩展属性
  - 11. 不支持元素清单
- 第三部分：Flowable 8 架构与使用
  - 12. 版本定位与总体架构
  - 13. 核心服务 API 与持久层
  - 14. Flowable 8 新特性
  - 15. Spring Boot 4 快速接入
  - 16. 运行机制与最佳实践
  - 17. 部署形态与生产落地清单
- 第四部分：DMN 决策模型与 Flowable 8 支持
  - 18. DMN 标准概览
  - 19. 命中策略（Hit Policy）
  - 20. Flowable DMN 引擎架构与支持范围
  - 21. Flowable 8 中使用 DMN
  - 22. 完整实战：折扣决策表
  - 23. 支持边界与最佳实践

---

# 第一部分：BPMN 2.0 节点类型详解

BPMN（Business Process Model and Notation）2.0 是工作流引擎（Flowable、Camunda、Activiti 等）事实上的建模标准。核心元素分四大类：**事件、活动、网关、连接对象**，外加泳道、数据对象等辅助元素。

## 1. 事件（Event）

事件表示流程中"发生的事情"，用圆圈表示。

### 1.1 按位置分类

| 类型 | 图形 | 作用 |
|---|---|---|
| **Start Event（开始事件）** | 细线单圆 | 流程实例的起点，一个流程至少一个 |
| **Intermediate Event（中间事件）** | 细线双圆 | 发生在流程执行过程中，分抛出型（throwing）和捕获型（catching） |
| **End Event（结束事件）** | 粗线单圆 | 流程分支的终点，可仅结束，也可附带结果（发消息、抛业务错误） |

### 1.2 按触发器分类（圆圈内部图标）

- **None（空事件）**：无特定触发器，流程启动即触发。
- **Message（消息，信封图标）**：通过消息（MQ 消息、API 调用）启动或推动流程，一对一定向投递，常用于跨流程、跨系统协作。
- **Timer（定时器，时钟图标）**：按时间点（`2026-08-10T09:00`）、持续时间（`PT3D`）或周期（`R5/PT1H`，部分引擎支持 cron）触发。审批超时、定时补偿都依赖它。
- **Signal（信号，三角图标）**：广播式触发——一个流程抛出，**所有当前活跃订阅该信号的流程实例**都会收到，与消息的一对一投递形成对比。三个坑：抛出是**同步事务内**执行（订阅者异常会连坐回滚，需异步可在 signalEventDefinition 上挂 `flowable:async="true"`）；只投给当下活跃的订阅（未启动 / 已结束的实例收不到）；**不跨集群节点广播**，多副本部署需自行分发或改用 Event Registry。
- **Error（错误，闪电图标）**：仅用于结束事件、边界事件和事件子流程起始事件，表示业务错误（区别于技术异常），可被错误边界事件捕获。
- **Escalation（升级，向上箭头）**：语义类似 Error，但不强制中断流程，常用于向上级上报。
- **Conditional（条件，三条线图标）**：当表达式条件成立（如 `amount > 10000`）时触发，由引擎持续评估。
- **Compensation（补偿，向左双三角）**：配合补偿边界事件实现 Saga 式回滚。
- **Terminate（终止，实心黑圆）**：终止**当前作用域**的所有并行分支——在子流程 / Call Activity 内只终止该子流程，配 `flowable:terminateAll="true"` 才终止整个流程实例；普通 End Event 只结束当前分支。
- **Link（链接，箭头图标）**：成对使用，替代跨页或过长的连线，纯图形组织用途。
- **Cancel（取消，X 图标）**：仅用于事务子流程。
- **Multiple / Parallel Multiple**：组合多种触发器；Parallel Multiple 要求多个触发器全部满足。

### 1.3 边界事件（Boundary Event）

附着在活动边框上的中间捕获事件，是实际建模中使用频率最高的机制之一：

- **中断型（实线双圆）**：触发后**中断**宿主活动，流程沿边界事件出口流转。典型场景：审批超时自动驳回、收到取消消息终止当前操作。
- **非中断型（虚线双圆）**：触发后宿主活动**继续执行**，同时并行开启一条新路径。典型场景：超时发送提醒，但继续等待审批结果。

## 2. 活动（Activity）

活动表示流程中"要做的工作"，用圆角矩形表示，分为任务（Task）和子流程（Sub-Process）。

### 2.1 Task 类型

| 任务 | 图标 | 说明 |
|---|---|---|
| **User Task** | 人形 | 需人工完成的任务（如审批），引擎生成待办，支持 assignee / candidate user / candidate group |
| **Service Task** | 齿轮 | 自动执行的系统任务，可绑定 Java 委托类、Spring Bean、表达式或外部服务 |
| **Script Task** | 脚本 | 在引擎内执行脚本（Groovy、JavaScript 等），适合轻量逻辑 |
| **Business Rule Task** | 表格 | 调用决策引擎（DMN 决策表、Drools） |
| **Send Task** | 实心信封 | 向外部参与者发送消息（邮件、MQ） |
| **Receive Task** | 空心信封 | 暂停等待外部消息，收到后继续 |
| **Manual Task** | 手形 | 完全在引擎外由人工执行（如线下盖章），引擎只记录流转 |
| **Call Activity**（粗边框） | — | 调用另一个**可复用的独立流程定义**。与子流程的区别：子流程内嵌于本流程，Call Activity 跨流程定义引用 |

### 2.2 Sub-Process（子流程）

- **嵌入式子流程**：内部可展开容纳完整流程片段，拥有自己的开始/结束事件，可访问父流程变量。
- **事务子流程（双边框）**：内部活动构成一个事务，配合 Cancel / Compensation 边界事件实现整体回滚。
- **事件子流程（虚线边框）**：由事件触发而非顺序流到达，详见 2.3。

### 2.3 事件子流程（Event Sub-Process）

事件子流程不由顺序流到达而启动，而是由事件触发。本质是"**作用域级的事件订阅器**"，三条核心机制：

- **与边界事件的区别**：边界事件挂在*某个活动*上，只在该活动执行期间接事件；事件子流程挂在*整个作用域*上，主流程走到作用域内任意一步都能接住——"状态无关"的兜底。
- **中断性**：由起始事件的 `isInterrupting` 属性控制（默认 `true`）。中断型触发后终止作用域内所有执行；非中断型主流程继续，处理器并行执行。
- **捕获范围由嵌套位置决定**：挂在流程根上 = 全流程兜底；挂在嵌入式子流程内 = 只兜该阶段（如只兜"支付阶段"的错误，其余错误继续上抛流程级处理）。

触发场景一览（各触发器在 Flowable 的支持情况见 7.1）：

| 触发事件 | 中断性 | 典型场景 |
|---|---|---|
| Error | 中断 | **统一异常处理**：作用域内任何活动抛出 BPMN Error 即进入兜底分支善后；嵌在子流程内可实现阶段级错误隔离 |
| Timer | 中断 | **全局超时处理**：SLA 违约，中断并升级处理 |
| Timer | 非中断 | **催办 / 提醒 / SLA 预警**：超 3 天未审批发提醒通知，主流程照常走——与中断型全局超时是一对 |
| Message | 中断 | **任意时刻撤销 / 取消**：申请人撤单、客户取消订单。不知道流程当前走到哪一步，无法逐活动挂边界事件，作用域级一个事件子流程全兜住 |
| Message / Signal | 非中断 | **动态干预**：审批中补料通知、改派、加签；全局信号广播（紧急暂停、政策变更触发重审） |
| VariableListener | 中断 / 非中断 | **变量条件监听**：如"客户信用降级为黑名单则中断当前审批"。Flowable 扩展事件，变量一变即触发；Conditional 起始事件引擎不分发 behavior（见 7.1） |
| Compensation | — | **补偿处理器**：事务子流程的 undo 逻辑建模为补偿事件子流程，配合 Cancel 边界事件实现整体回滚 |

事件子流程也是 Multiple 类不支持元素的替代建模手段（见第 11 章结论）：

- **Multiple / Parallel Multiple 起始事件不支持** → 用事件子流程实现"多个事件谁先来听谁的"。

"升级上报"场景则可直接用 **Escalation 起始事件**（事件子流程内，Flowable 8 已支持，见 7.1）：子流程内抛出升级，非中断型事件子流程捕获后通知上级介入，主流程不中断。

> **选型口诀**：只关心某个活动 → 边界事件；关心整个作用域的任意时刻 → 事件子流程。

### 2.4 多实例标记（Multi-Instance）

活动上的三条竖线或横线，表示该活动对集合中每个元素各执行一次：

- **并行多实例（三条竖线）**：所有实例同时创建。典型场景：三人会签。
- **顺序多实例（三条横线）**：按顺序逐个执行。典型场景：串行多级审批。
- 通过 **completionCondition** 配置提前完成条件，实现"过半同意即结束"的或签。

### 2.5 循环标记（Loop）

圆箭头图标：按条件重复执行该活动，直到条件不满足。

## 3. 网关（Gateway）

网关控制流程的分支与汇聚，用菱形表示。**网关不做业务处理，只做路由**。

| 网关 | 图标 | 语义 |
|---|---|---|
| **Exclusive（排他，XOR）** | X | 只走**一条**满足条件的出口路径，可配默认流；汇聚时先到先过。最常用，等价于 if-else |
| **Parallel（并行，AND）** | + | **所有**出口同时执行；汇聚时等待**所有**入口分支到达（fork-join） |
| **Inclusive（包容，OR）** | O | 走**所有满足条件**的出口；汇聚时等待所有可能到达的分支。灵活但语义复杂，慎用 |
| **Event-Based（事件网关）** | 圆内五边形 | 出口必须接捕获事件，**哪个事件先发生走哪条路**。典型场景："等待回调或超时"二选一 |
| **Complex（复杂）** | * | 自定义汇聚表达式，实际项目极少使用 |

## 4. 连接对象与泳道

- **Sequence Flow（顺序流）**：实线箭头，同一流程内的执行顺序，可挂条件表达式（如 `${approved == true}`）。
- **Message Flow（消息流）**：虚线箭头，表示**跨 Pool** 的通信，不允许在同一 Pool 内部使用。
- **Association（关联）**：点线，把数据对象、文本注解挂接到流程元素上。
- **Pool（池）**：一个参与者或系统的边界，跨 Pool 只能用 Message Flow。
- **Lane（泳道）**：Pool 内的职责划分，如"申请人""部门经理""财务"。
- **Data Object / Data Store**：业务数据与持久化存储引用；**Text Annotation**：注释。

## 5. 典型组合模式

以下模式覆盖企业审批流中 80% 以上的建模场景：

1. **超时审批**：User Task + 中断型 Timer Boundary Event → 自动驳回或升级上级。
2. **等待外部回调**：Event-Based Gateway → [Message Catch Event | Timer Catch Event]，先到先走。
3. **会签 / 或签**：User Task 配置并行多实例，completionCondition 控制或签。
4. **服务编排回滚**：Service Task + Compensation Boundary Event + 事务子流程，实现分布式 Saga。
5. **技术异常 vs 业务错误**：技术异常（网络超时）交给引擎重试策略；业务错误（库存不足）显式建模为 BPMN Error，走错误边界事件分支。

## 6. 元素速查表

| 大类 | 图形 | 包含元素 |
|---|---|---|
| 事件 | 圆圈 | Start / Intermediate / End / Boundary × None / Message / Timer / Signal / Error / Escalation / Conditional / Compensation / Terminate / Link / Cancel |
| 活动 | 圆角矩形 | User / Service / Script / BusinessRule / Send / Receive / Manual Task、Call Activity、Sub-Process（嵌入式 / 事务 / 事件）、多实例、循环 |
| 网关 | 菱形 | Exclusive / Parallel / Inclusive / Event-Based / Complex |
| 连接 | 线条 | Sequence Flow / Message Flow / Association |
| 辅助 | 容器与图形 | Pool / Lane / Data Object / Data Store / Text Annotation |

---

# 第二部分：Flowable 对 BPMN 元素的支持矩阵

Flowable 宣称完整支持 BPMN 2.0 标准，实际执行语义是"**可执行子集 + Flowable 扩展**"：绝大多数元素可执行，少数仅做图形解析或不支持。

## 7. 事件支持

### 7.1 开始事件

| 触发器 | 支持情况 | 说明 |
|---|---|---|
| None | ✅ | |
| Message | ✅ | 通过 `runtimeService.startProcessInstanceByMessage()` 触发 |
| Timer | ✅ | 支持 ISO 8601 表达式与 cron |
| Signal | ✅ | 支持全局/流程定义级信号订阅 |
| Error | ✅ | 仅用于事件子流程内 |
| Escalation | ✅ | 引擎已支持（7.x 起，含事件子流程起始）；官方 Constructs 用户文档未收录章节，以源码为准 |
| Conditional | ❌ | 起始事件不支持（含事件子流程内，引擎不分发 behavior）；仅边界事件与中间捕获事件可用（见 7.2） |
| Compensation | ⚠️ 有限 | 仅用于事件子流程/边界，不作为顶级开始事件 |
| Multiple / Parallel Multiple | ❌ | 用多个独立开始事件或事件子流程替代 |

### 7.2 中间事件与边界事件

- **Catching**：Timer、Message、Signal、Conditional 支持；另有 Flowable 扩展 **VariableListener**（变量变化即触发，边界 / 中间捕获 / 事件子流程起始均支持）。
- **Throwing**：None、Signal、Compensation、Escalation 支持；Message Throw 需借助 Service Task 委托代码或事件注册表（Event Registry）扩展实现。
- **边界事件**：Timer、Error、Signal、Message、Compensation、Cancel（事务子流程）、Conditional、Escalation 均支持，中断型与非中断型皆可（非中断型用 `cancelActivity="false"`）。
- **Link 中间事件**：❌ 无执行语义。

### 7.3 结束事件

✅ None、Error、Escalation、Terminate、Cancel 支持；❌ Message、Signal、Compensation 结束事件无执行语义（解析后落到 None 结束行为，不投递、不回滚）。

## 8. 活动支持

### 8.1 标准 Task

| 任务 | 支持情况 | 说明 |
|---|---|---|
| User Task | ✅ | assignee / candidate、dueDate、priority、taskListener、表单完整支持 |
| Service Task | ✅ | class / expression / delegateExpression 三种绑定，支持 async、skipExpression |
| Script Task | ✅ | 支持脚本输入变量 |
| Business Rule Task | ✅ | 对接 Flowable DMN 引擎 |
| Receive Task | ✅ | 支持 skipExpression |
| Send Task | ✅ | 通常配合事件注册表或自定义实现 |
| Manual Task | ✅ | |

### 8.2 Flowable 扩展任务（非标准 BPMN，开箱即用）

- **Http Task**：调用 REST 服务，支持安全头脱敏。
- **Mail Task**：发送邮件，支持字符串集合/JSON 数组收件人。
- **Camel Task / Shell Task**：集成 Apache Camel、执行 Shell。
- **External Worker Task**：外部工作器模式。
- **DMN 决策调用**：开源版通过 `flowable:type="dmn"` 的 Service Task + field 注入 `decisionTableReferenceKey` 调用 DMN 决策表（**正统写法见第 20.3 节**，businessRuleTask 属性形式不可用）；独立的 Decision Task / Case Task 是企业版 Design 建模器概念，开源版无此任务类型。

### 8.3 子流程与多实例

- ✅ 嵌入式子流程、事件子流程（触发场景见 2.3）、事务子流程、Ad-hoc 子流程。
- ✅ Call Activity：支持 in/out 参数映射、businessKey 继承、变量继承、多租户回退。
- ✅ 并行/顺序多实例：loopCardinality、collection、elementVariable、completionCondition。
- ✅ 标准循环（loopCharacteristics）。

## 9. 网关与连接支持

| 元素 | 支持情况 | 说明 |
|---|---|---|
| Exclusive 网关 | ✅ | 支持默认流 |
| Parallel 网关 | ✅ | fork-join 语义完整 |
| Inclusive 网关 | ✅ | 多条件出口与汇聚等待均支持 |
| Event-Based 网关 | ✅ | 出口只能接中间捕获事件（Timer/Message/Signal/Conditional）；官方明示接 Receive Task 不支持 |
| **Complex 网关** | ❌ 不可执行 | XML 可解析但无行为语义，需用其他网关组合替代 |
| Sequence Flow | ✅ | 支持条件表达式（EL）、skipExpression |
| Message Flow / Pool / Lane | ⚠️ 仅图形信息 | 引擎不基于 Pool/Lane 做执行路由或权限控制 |
| Data Object | ⚠️ 可解析 | 运行时语义有限，业务数据走流程变量机制 |

## 10. Flowable 扩展属性（`flowable:` 命名空间）

Flowable 超越标准 BPMN 的部分，专有属性通过 `flowable:` 命名空间注入，不影响 XML 在其他建模工具中的可读性：

- **流程级**：`candidateStarterUsers/Groups`（发起权限）、event listeners。
- **执行控制**：`async`（异步节点）、`exclusive`（排他作业）、`skipExpression`（动态跳过）、`failedJobRetryTimeCycle`（失败重试策略）、`terminateAll`（Terminate End Event 终止范围扩至整个实例，见 1.2）。
- **监听器**：executionListener（start/end/take）、taskListener（create/assignment/complete/delete）。
- **用户任务**：assignment 全套属性、表单（formKey / form properties）。
- **事件注册表（Event Registry）**：Kafka/JMS 通道建模，弥补标准 BPMN 在消息中间件集成上的空白。

## 11. 不支持或需注意的清单

| 元素 | 状态 |
|---|---|
| Complex Gateway | ❌ 不可执行 |
| Multiple / Parallel Multiple 事件 | ❌ 不支持 |
| Link Intermediate Event | ❌ 无执行语义 |
| Escalation 事件（Start / Boundary / Intermediate / End） | ⚠️ 引擎已支持（7.x 起），但官方 Constructs 用户文档无章节——查文档时勿误判为不支持 |
| Conditional Start Event（含事件子流程内） | ❌ 引擎不分发 behavior；仅边界事件与中间捕获事件可用，变量监听场景用 VariableListener 事件替代 |
| Conversation / Choreography 图 | ❌ 完全不支持 |
| Pool / Lane | ⚠️ 仅图形信息，无执行语义 |
| Data Object | ⚠️ 可解析，运行时语义弱 |
| Message Intermediate Throw | ⚠️ 需扩展实现（Event Registry / 委托代码） |

> **结论**：Flowable 开源版对 BPMN 的覆盖足以支撑绝大多数企业审批流与服务编排场景。日常建模遇到的限制主要有 Complex Gateway、Multiple 事件两类，且都有明确的替代方案；Escalation 与 Conditional 需注意官方用户文档滞后于引擎实现。

---

# 第三部分：Flowable 8 架构与使用

## 12. 版本定位与总体架构

### 12.1 Flowable 8 是什么

Flowable 是用 Java 编写的轻量级开源业务流程与工作流引擎（源自 Activiti 5 同源团队），覆盖 **BPMN 2.0（流程）、CMMN（案例）、DMN（决策表）、表单、事件注册表** 五大建模标准。8.0 的核心变化是基础设施全面升级，而非引擎语义重写：

| 维度 | Flowable 7 | Flowable 8 |
|---|---|---|
| Spring Boot | 3.x | **4.x（不再支持 Boot 3）** |
| Spring Framework | 6.x | 7.x |
| JSON 库 | Jackson 2 | **Jackson 3**（2 通过兼容层仍可用） |
| 测试框架 | JUnit 4/5 | **仅 JUnit 5** |
| Java 基线 | 17 | 17+ |
| 表达式能力 | EL | 新增 **Lambda 表达式**、**Java Records** 支持 |
| 可观测性 | 标准历史 | 新增 `endUserId`、历史流程实例 `state`、结束拦截器 |

### 12.2 引擎层（多引擎独立可插拔）

```
┌──────────────────────────────────────────────────────┐
│                    Spring Boot 应用                    │
│  ┌──────────────┬──────────────────────────────────┐ │
│  │ 业务代码      │ Flowable Spring Boot Starter     │ │
│  │ （Service/   │  ┌─────────┐ ┌─────┐ ┌─────────┐ │ │
│  │  REST/事件）  │  │ BPMN    │ │CMMN │ │ DMN     │ │ │
│  │              │  │ Engine  │ │Eng. │ │ Engine  │ │ │
│  │              │  ├─────────┼─────┤─┼─────────┤ │ │
│  │              │  │ Form Eng│ IDM │ │ Event   │ │ │
│  │              │  │         │ Eng │ │ Registry│ │ │
│  │              │  └─────────┴─────┴─┴─────────┘ │ │
│  └──────────────┴──────────────────────────────────┘ │
├──────────────────────────────────────────────────────┤
│  持久层：MyBatis 映射 + 关系库（ACT_* 表族）            │
│  异步层：Async Executor / Timer Job / Event Registry   │
└──────────────────────────────────────────────────────┘
```

各引擎共享同一套基础设施（数据源、事务、异步执行器、历史、事件总线），可按需裁剪——只用 BPMN 就引入 process starter，避免整套引擎全量加载。

### 12.3 异步执行器（Async Executor）

定时器边界事件、`async=true` 节点、事件注册表入站等全部由异步执行器落到 `ACT_RU_JOB / ACT_RU_TIMER_JOB` 轮询执行，支持多节点部署时的全局锁竞争与 job 认领，天然适配 K8s 多副本。

## 13. 核心服务 API 与持久层

### 13.1 六大核心服务

| 服务 | 职责 |
|---|---|
| `RepositoryService` | 流程定义部署、查询、挂起、流程图导出 |
| `RuntimeService` | 启动/删除流程实例、消息与信号投递、流程变量 |
| `TaskService` | 用户任务的查询、认领（claim）、完成、委派 |
| `HistoryService` | 历史流程实例、活动、任务、变量查询 |
| `ManagementService` | 作业（job）管理、死信处理、表结构元数据 |
| `IdentityService` | 用户/组管理（生产环境一般对接企业 IDM，弃用内置） |

### 13.2 表族结构（ACT_* 前缀）

- `ACT_RE_*`：Repository，流程定义与部署包（如 `ACT_RE_PROCDEF`）。
- `ACT_RU_*`：Runtime，运行期数据——实例（`ACT_RU_EXECUTION`）、任务（`ACT_RU_TASK`）、变量、事件订阅；**实例结束后即清理**，保证运行表小、性能稳定。
- `ACT_HI_*`：History，历史数据，由 `flowable.history-level` 控制粒度（none / activity / audit / full）。
- `ACT_GE_*`：General，字节数组、属性表。
- `ACT_ID_*`：IDM 身份数据；`ACT_DMN_*`：DMN 决策（注意 `ACT_DE_*` 是企业版 Design 建模器的表，非 DMN 引擎）；`ACT_CMMN_*` / `ACT_FO_*` / `ACT_EV_*` 对应其余引擎。

生产库支持 MySQL、PostgreSQL、Oracle、MSSQL、DB2；Oracle 环境可用 `flowable.oracle-lob-handler` 适配 LOB 处理。

## 14. Flowable 8 新特性速览

1. **表达式 Lambda 支持**：Flowable 8 表达式引擎扩展了对 Lambda 与 Stream 式调用的支持，可在 EL 中对集合做流式过滤/映射（具体语法以引擎版本为准，下为示意）——`${customers.stream().filter(c -> c.type == 'premium').toList()}`。
2. **Java Records 作为流程变量/表达式对象**。
3. **端用户跟踪**：历史流程/案例实例新增 `endUserId`，历史流程实例新增 `state`。
4. **结束拦截器**：流程/案例结束时可注入自定义拦截逻辑。
5. **REST 日期统一为 UTC + 毫秒精度**（ISO 8601），跨时区对账更可靠。
6. **迁移增强**：实例迁移支持更新更多任务属性，校验逻辑暴露到 REST API。
7. **事件注册表 hookpoints**：可自定义事件 payload 类型的处理钩子。
8. 性能：表达式类型转换提速、长字符串变量缓存、复用 ObjectMapper。

## 15. Spring Boot 4 快速接入

### 15.1 Maven 依赖

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
</parent>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <!-- 全套引擎；只用 BPMN 可换成 flowable-spring-boot-starter-process -->
    <dependency>
        <groupId>org.flowable</groupId>
        <artifactId>flowable-spring-boot-starter</artifactId>
        <version>8.0.0</version>
    </dependency>
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
    </dependency>
</dependencies>
```

### 15.2 最小配置（application.yml）

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/flowable?characterEncoding=UTF-8
    username: flowable
    password: flowable
flowable:
  database-schema-update: true      # 首次启动自动建表，生产建议 false + Flyway/Liquibase 管理
  history-level: audit              # none / activity / audit / full
  async-executor-activate: true     # 开启异步执行器（定时器、async 节点必需）
  check-process-definitions: true   # 启动时校验流程定义存在；processes/ 目录的自动部署由独立的 auto-deployment 机制完成
```

### 15.3 放置流程定义

BPMN 文件放入 `src/main/resources/processes/`，命名 `*.bpmn20.xml`，启动即自动部署。

### 15.4 典型调用代码

```java
@SpringBootApplication
public class WorkflowApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkflowApplication.class, args);
    }
}

@Service
@RequiredArgsConstructor
public class LeaveService {

    private final RuntimeService runtimeService;
    private final TaskService taskService;

    // 发起请假流程
    public String startLeave(String employee, int days) {
        Map<String, Object> vars = Map.of("employee", employee, "days", days);
        ProcessInstance pi = runtimeService.startProcessInstanceByKey("leaveProcess", vars);
        return pi.getId();
    }

    // 经理待办列表
    public List<Task> managerTodo() {
        return taskService.createTaskQuery()
                .taskCandidateGroup("managers")
                .active()
                .list();
    }

    // 审批
    public void approve(String taskId, boolean approved) {
        taskService.complete(taskId, Map.of("approved", approved));
    }
}
```

### 15.5 Service Task 绑定 Spring Bean

```java
@Component("notificationDelegate")
public class NotificationDelegate implements JavaDelegate {
    @Override
    public void execute(DelegateExecution execution) {
        String employee = (String) execution.getVariable("employee");
        // 发送通知……
    }
}
```

```xml
<serviceTask id="notify" name="发送通知"
             flowable:delegateExpression="${notificationDelegate}"/>
```

## 16. 运行机制与最佳实践

### 16.1 流程变量

- 简单类型、序列化对象、JSON 均可作为变量；大对象用 `Serializable` 或独立表 + 变量存 ID。
- 变量作用域：流程实例级 / 执行（分支）级 / 任务本地（`taskService.setVariableLocal`）。

### 16.2 事务与边界

- 引擎操作与业务代码共用 Spring 事务：同一事务内 `complete()` 与业务表写入**要么一起提交要么一起回滚**。
- 跨事务边界用 `flowable:async="true"` 打断点，失败由 Async Executor 按 `failedJobRetryTimeCycle="R3/PT5M"` 重试，耗尽进死信表。

### 16.3 监听器

- **ExecutionListener**：流程/节点 start、end、连线 take 事件。
- **TaskListener**：create / assignment / complete / delete。
- **全局事件监听**：`FlowableEventListener` 订阅引擎事件总线，适合统一埋点、审计、对接 Splunk。

### 16.4 多实例会签

```xml
<userTask id="approve" name="会签" flowable:candidateGroups="reviewers">
  <multiInstanceLoopCharacteristics isSequential="false"
        flowable:collection="${reviewerList}" flowable:elementVariable="reviewer">
    <completionCondition>${nrOfCompletedInstances == nrOfInstances}</completionCondition>
  </multiInstanceLoopCharacteristics>
</userTask>
```

多实例体内可直接引用引擎注入的内建变量：

| 变量 | 含义 |
|---|---|
| `nrOfInstances` | 总实例数 |
| `nrOfActiveInstances` | 未完成实例数 |
| `nrOfCompletedInstances` | 已完成实例数 |
| `loopCounter` | 顺序多实例的当前索引（并行多实例无意义） |

completionCondition 用它们表达或签：`${nrOfCompletedInstances > nrOfInstances / 2}`（过半即过）；按票数裁决时需自维护计数变量（TaskListener 累加）——内建变量只统计"完成数"，不区分同意与否。

### 16.5 超时与补偿

- 审批超时：User Task 挂**中断型定时边界事件** → 自动驳回/升级分支。
- 分布式回滚：Service Task + **补偿边界事件** + 事务子流程实现 Saga。

### 16.6 测试（仅 JUnit 5）

`@FlowableTest` 需引入 `flowable-spring-boot-starter-test`（15.1 的依赖清单未含）：

```java
@FlowableTest
class LeaveProcessTest {

    @Test
    void approvePath(ProcessEngine engine) {
        RuntimeService runtime = engine.getRuntimeService();
        TaskService tasks = engine.getTaskService();

        ProcessInstance pi = runtime.startProcessInstanceByKey("leaveProcess",
                Map.of("employee", "zhangsan", "days", 2));

        Task managerTask = tasks.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        tasks.complete(managerTask.getId(), Map.of("approved", true));

        assertThat(runtime.createProcessInstanceQuery().processInstanceId(pi.getId()).count()).isZero();
    }
}
```

### 16.7 流程定义版本化与实例迁移

- 同 key 重新部署自动产生新版本（`ACT_RE_PROCDEF.VERSION_` 递增），**存量实例继续走其启动时的版本**，不会自动迁移——"部署了新流程，老单子还在走旧流程"是最常见的认知坑。
- 存量实例需要走新流程时用迁移 API：`ProcessMigrationService.createProcessInstanceMigrationBuilder()` 构建，`migrate()` 前先 `validateMigration()` 预校验；拓扑有变化时用 `addActivityMigrationMapping()` 显式声明新旧活动映射。
- 迁移失败多因新旧版本活动 id 对不上——设计期保持活动 id 稳定（只加不改不删），迁移成本可大幅降低。

## 17. 部署形态与生产落地清单

### 17.1 部署形态

| 形态 | 说明 |
|---|---|
| **嵌入式（推荐）** | 引擎作为依赖内嵌业务服务，共享数据源与事务，性能最好 |
| **flowable-rest Docker** | `docker run -p 8080:8080 flowable/flowable-rest`，开箱 REST API（账号 rest-admin/test） |
| **flowable-ui Docker** | 含 Modeler / Task / Admin / IDM 四个 UI 应用，适合建模调试（账号 admin/test） |
| **K8s 多副本** | 无状态嵌入部署，Async Executor 通过 DB 锁自动协调 job 分配 |

> Docker 镜像的默认凭据（rest-admin/test、admin/test）仅限本地评估，生产环境必须修改或禁用。

### 17.2 生产落地清单

1. **表结构变更**：`database-schema-update` 仅开发期开启，生产用 Flyway/Liquibase 管理 `ACT_*` 演进脚本。
2. **历史数据治理**：设定合理 `history-level`，定期归档 `ACT_HI_*`，避免历史表膨胀。
3. **身份集成**：禁用内置 IDM，通过自定义 `UserEntityManager` 或任务查询拦截对接企业组织架构。
4. **监控告警**：Async Executor 死信数、作业堆积量、定时器延迟纳入监控体系。
5. **版本升级路径**：Boot 2 系统先上 6.8.x；Boot 3 系统上 7.2.x；**Boot 4 / 新项目直接上 8.0**。
6. **业务与技术异常分离**：业务错误用 BPMN Error 显式建模，技术异常交给重试与死信机制。

### 17.3 版本速查

| 你的现状 | 建议版本 |
|---|---|
| Spring Boot 2.7 存量系统 | Flowable 6.8.x（最后一个支持 Boot 2 的主线） |
| Spring Boot 3 / Java 17 | Flowable 7.2.x |
| Spring Boot 4 / Spring Framework 7 | **Flowable 8.0.x** |
| 需要 Lambda / Records 表达式、端到端用户跟踪 | **Flowable 8.0.x** |


---

# 第四部分：DMN 决策模型与 Flowable 8 支持

## 18. DMN 标准概览

DMN（Decision Model and Notation）是 OMG 发布的决策建模标准，目标是把"可重复的业务决策"（如折扣计算、风险评级、审批路由）从代码和流程中剥离出来，用**决策表**形式表达，使业务人员可直接维护规则、IT 与业务共用同一套模型。

### 18.1 核心概念

| 概念 | 说明 |
|---|---|
| **definitions** | DMN 文件的根元素，可包含多个决策定义（实践建议：一个文件只放一个决策，便于维护） |
| **decision（决策）** | 一个具名的决策定义，拥有唯一 key，通过 key 被流程或代码引用 |
| **decision table（决策表）** | Flowable 当前支持的决策表达式类型：左侧输入列、右侧输出列、每行一条规则 |
| **input（输入列）** | 绑定一个输入变量（如 `customerCat`），声明类型（string/number/boolean/date）与可选枚举值 |
| **output（输出列）** | 声明输出变量名（如 `discountPerc`）与类型，可定义有序输出值列表（供优先级策略使用） |
| **rule（规则行）** | 各输入单元格（input entry）以 AND 连接；`-` 表示该输入不参与匹配（恒真） |
| **hit policy（命中策略）** | 表格左上角标记，决定多条规则同时命中时如何产出结果 |
| **DRG / DRD** | 决策需求图：多个决策之间依赖关系的图形化表达。**Flowable 开源版不支持**，需用 BPMN 编排多个决策任务替代 |

**Flowable 的 DMN 方言**：XML 序列化采用 DMN 1.1 命名空间（`http://www.omg.org/spec/DMN/20151101`，见第 22 章示例），表达式全部用 JUEL（非 FEEL，见 20.2），可执行的决策类型只有决策表与决策服务（见 20.4）两种——把它当作「DMN 1.1 子集 + Flowable 扩展」理解最准确。

### 18.2 决策表的求值逻辑

1. 对每行规则：所有 input entry 与传入变量求布尔值，**全真则该行命中**。
2. 收集所有命中行，按 hit policy 计算出最终输出。
3. 若没有任何规则命中：返回空结果（DMN 标准允许定义 default output，Flowable 开源版未实现，用一条 `-` 兜底规则代替）。

## 19. 命中策略（Hit Policy）

Flowable 完整支持 7 种标准命中策略，分单命中与多命中两类：

### 19.1 单命中（Single Hit）

| 策略 | 语义 | 适用场景 |
|---|---|---|
| **UNIQUE（U）** | 规则不允许重叠，至多一条命中；多条命中视为错误 | 互斥分类规则，最常用、最严格 |
| **FIRST（F）** | 允许多条命中，按规则顺序返回**第一条**，命中即停止求值 | "按优先级排列的特例 + 末尾兜底"模式 |
| **ANY（A）** | 允许重叠，但所有命中行输出必须相同；不同则结果为空并标记失败 | 冗余校验型规则 |
| **PRIORITY（P）** | 允许多条命中，返回**输出值优先级最高**的行（优先级由输出值列表顺序定义，与规则顺序无关） | 按结果严重程度裁决（如取最高风险等级） |

### 19.2 多命中（Multiple Hit）

| 策略 | 语义 |
|---|---|
| **RULE ORDER（R）** | 按规则顺序返回所有命中行的输出列表 |
| **OUTPUT ORDER（O）** | 按输出值优先级降序返回所有命中行 |
| **COLLECT（C）** | 无序返回所有命中行；可附加聚合操作符：`+` 求和、`<` 取最小、`>` 取最大、`#` 计数 |

### 19.3 版本陷阱：COLLECT `#` 计数语义

DMN 1.1 规范中 `#` 统计的是**去重后**的输出数；DMN 1.2 起改为统计**全部**输出数。Flowable 引擎默认采用 1.2 语义（不去重），旧行为经 Decision 元素的 `forceDMN11` 标志开启（Flowable Modeler 保存决策表时勾选 **Force DMN 1.1** 即设置该标志）。此外聚合结果（SUM/MIN/MAX/COUNT）一律返回 Double（如 `2.0` 而非 `2`），类型敏感的下游代码需注意。

## 20. Flowable DMN 引擎架构与支持范围

### 20.1 引擎结构

DMN 引擎与 BPMN 引擎平级，共享数据源、事务与部署基础设施：

- **DmnEngineConfiguration**：引擎配置枢纽，内置 7 种命中策略实现（继承 `AbstractHitPolicy`，可扩展自定义策略）。
- **DmnRepositoryService**：决策模型的部署、存储、查询（`.dmn` / `.dmn.xml` 文件解析入库）。
- **DmnDecisionService**：决策执行门面——按 key 执行单个决策、多结果执行、带审计日志执行。
- **DmnHistoryService**：决策执行历史与审计查询（每次执行的输入输出快照）。
- **DmnManagementService**：引擎管理与缓存。
- 表族：`ACT_DMN_DEPLOYMENT` / `ACT_DMN_DECISION`（部署与决策定义）、`ACT_DMN_HI_DECISION_EXECUTION`（执行历史）。

### 20.2 支持范围矩阵

| 能力 | 支持情况 | 说明 |
|---|---|---|
| 决策表（Decision Table） | ✅ 完整支持 | 输入/输出/注解列、多输入多输出 |
| 7 种命中策略 | ✅ 全部支持 | 含 COLLECT 聚合操作符 |
| 数据类型 | ✅ | string / number / boolean / date，支持枚举值约束 |
| 表达式语言 | ⚠️ **JUEL，非 FEEL** | 输入条目写 JUEL 风格表达式（`== "GOLD"`、`> 100`）；输出条目支持 `${变量}` 引用流程变量，可拼装动态 JSON/字符串 |
| 决策链 / DRG（DRD） | ❌ 不支持 | 用 BPMN 多个 Decision Task 串联替代 |
| 字面表达式 / Boxed Context / 关系 | ❌ 不支持 | 仅决策表一种表达式类型 |
| 默认输出值（default output） | ❌ 未实现 | 用 `-` 全匹配兜底规则代替 |
| 决策服务（Decision Service） | ✅ | 支持 decisionService 元素封装可复用决策集 |
| 执行审计 | ✅ | 每次执行的输入/输出可落历史库，支持审计追踪 |

### 20.3 与 BPMN 的集成方式

BPMN 任务通过 **`flowable:type="dmn"` + field 注入**引用决策表 key（**8.0.0 实测口径**：`businessRuleTask` 上的 `flowable:decisionTableReferenceKey` 属性**没有解析者**——BRT 固定落入 KIE 规则路径，部署无错但执行报 `deployment doesn't contain any rules`；须用 serviceTask 形式）：

```xml
<serviceTask id="determineDiscount" name="计算折扣" flowable:type="dmn">
  <extensionElements>
    <flowable:field name="decisionTableReferenceKey" stringValue="DetermineDiscount"/>
  </extensionElements>
</serviceTask>
```

- 流程变量自动作为决策表输入（变量名与输入列表达式一致）。
- 单命中：输出按**输出列名**直接写回流程变量（如 `discountPerc`）。
- 多命中（COLLECT / RULE ORDER 等）：结果构造成 JSON 数组（每个命中行一个对象，key 为输出列名），存入以**决策 key** 命名的变量——`flowable:resultVariableName` 仅 Drools 版 Business Rule Task 读取，DMN 路径忽略该属性，勿写。
- 无命中兜底二选一：`-` 兜底规则（见第 22 章）之外，还可给任务配 field `decisionTaskThrowErrorOnNoHits`（`stringValue="true"` 或布尔表达式），决策表无命中时直接抛 `FlowableException`，把"表不完备"暴露为显式失败。
- 决策表与流程分别部署、独立版本化——改规则不改流程，改流程不改规则。

### 20.4 决策服务

DMN 1.2 的 decisionService（一组决策的封装：`outputDecisions` 暴露最终结果，`encapsulatedDecisions` 仅内部复用不外露）Flowable 已支持，Java 侧经 `ExecuteDecisionBuilder` 执行——`decisionKey` 指向决策服务 key：

```java
// 返回 Map<决策 key, List<Map<输出列, 值>>>，outputDecisions 的结果按 key 分组
Map<String, List<Map<String, Object>>> grouped = decisions
        .createExecuteDecisionBuilder()
        .decisionKey("OrderPricingService")
        .variables(vars)
        .executeDecisionService();

// 变体：executeDecisionServiceWithSingleResult()（每组取首行）、executeDecisionServiceWithAuditTrail()（带审计容器）
```

多决策编排仍以 BPMN 多个决策任务串联为主流（DRG 不支持，见 20.2）；决策服务适合「一次调用、齐取多张表」的场景。

## 21. Flowable 8 中使用 DMN

### 21.1 依赖与部署目录

```xml
<!-- 全套 starter 已包含 DMN 引擎；单独使用则用： -->
<dependency>
    <groupId>org.flowable</groupId>
    <artifactId>flowable-spring-boot-starter-dmn</artifactId>
    <version>8.0.0</version>
</dependency>
<!-- 需要 DMN REST API 时用 flowable-spring-boot-starter-dmn-rest -->
```

**关键陷阱**：DMN 文件必须放在 `src/main/resources/dmn/` 目录（而非 `processes/`），后缀 `.dmn` 或 `.dmn.xml`，启动时自动部署。放错目录会导致 Business Rule Task 执行时报 `No decision found for key: xxx`。可用 REST 端点 `GET /dmn-repository/decision-tables` 验证部署结果。

### 21.2 配置项

```yaml
flowable:
  dmn:
    database-schema-update: true
    history-enabled: true        # 记录每次决策执行的输入输出，便于审计
    strict-mode: true            # 默认 true：UNIQUE/ANY/PRIORITY/OUTPUT ORDER 违规抛 FlowableException；false 宽容取最后一条有效规则
```

### 21.3 Java 调用（独立于流程）

```java
@Service
@RequiredArgsConstructor
public class PricingService {

    private final DmnDecisionService dmnDecisionService;

    public double calcDiscount(String customerCat) {
        Map<String, Object> result = dmnDecisionService
                .createExecuteDecisionBuilder()
                .decisionKey("DetermineDiscount")
                .variable("customerCat", customerCat)
                .executeWithSingleResult();
        return ((Number) result.get("discountPerc")).doubleValue();
    }

    // 带审计的执行：history-enabled=true 时每次执行的输入输出落历史库，
    // executeWithAuditTrail() 返回含规则命中明细的结果，满足风控/定价类合规追踪
    public void calcAndAudit(String customerCat) {
        dmnDecisionService
                .createExecuteDecisionBuilder()
                .decisionKey("DetermineDiscount")
                .variable("customerCat", customerCat)
                .executeWithAuditTrail();
        // 审计快照已写入 ACT_DMN_HI_DECISION_EXECUTION，
        // 经 DmnHistoryService.createHistoricDecisionExecutionQuery() 查询
    }
}
```

### 21.4 REST 调用

```bash
curl -u rest-admin:test http://localhost:8080/flowable-rest/dmn-rule/execute-decision \
  -H 'Content-Type: application/json' \
  -d '{
    "decisionKey": "DetermineDiscount",
    "inputVariables": [
      { "name": "customerCat", "type": "string", "value": "GOLD" }
    ]
  }'
```

返回 `resultVariables` 数组；DMN REST 日期格式在 Flowable 8 中统一为 UTC 毫秒精度 ISO 8601。

### 21.5 独立嵌入（无 Spring Boot）

DMN 引擎可脱离 Spring Boot 独立运行，轻量嵌入纯 Java 服务做规则求值：

```java
// 内存库：测试 / 临时求值
DmnEngine engine = DmnEngineConfiguration.createStandaloneInMemDmnEngineConfiguration()
        .setDatabaseSchemaUpdate("true")
        .buildDmnEngine();

// 外部数据库：生产
DmnEngine engine = DmnEngineConfiguration.createStandaloneDmnEngineConfiguration()
        .setJdbcUrl("jdbc:mysql://localhost:3306/dmn")
        .setJdbcUsername("dmn").setJdbcPassword("dmn")
        .setDatabaseSchemaUpdate("true")   // 生产同 BPMN：false + 迁移脚本管理
        .buildDmnEngine();

DmnDecisionService decisions = engine.getDmnDecisionService();
DmnRepositoryService repository = engine.getDmnRepositoryService();
DmnHistoryService history = engine.getDmnHistoryService();
```

独立模式下没有 classpath 自动扫描，部署走 API：`repository.createDeployment().addClasspathResource("dmn/DetermineDiscount.dmn").deploy()`。

### 21.6 历史与审计查询

`flowable.dmn.history-enabled: true` 时每次执行的输入输出落 `ACT_DMN_HI_DECISION_EXECUTION`，经 `DmnHistoryService` 查询：

```java
List<DmnHistoricDecisionExecution> executions = history
        .createHistoricDecisionExecutionQuery()
        .decisionKey("DetermineDiscount")                  // 按决策表过滤
        .processInstanceIdWithChildren(processInstanceId)   // 按流程实例关联（含子执行）
        .orderByStartTime().desc()
        .list();
```

- 查询维度覆盖 `decisionKey` / `decisionDefinitionId` / `deploymentId` / `instanceId` / `activityId` / `processInstanceIdWithChildren` / `caseInstanceIdWithChildren`；排序支持 `orderByStartTime` / `orderByEndTime` / `orderByTenantId`。
- 该查询同时实现 `DeleteQuery`，支持按条件清理历史，配合定期归档使用（见 17.2 历史数据治理）。

## 22. 完整实战：折扣决策表

`src/main/resources/dmn/DetermineDiscount.dmn`（DMN 1.1 命名空间示例）：

```xml
<definitions xmlns="http://www.omg.org/spec/DMN/20151101"
             id="definition_discount" name="Determine Discount"
             namespace="http://www.flowable.org/dmn">
  <decision id="DetermineDiscount" name="Determine Discount">
    <decisionTable id="determineDiscountTable1" hitPolicy="UNIQUE">
      <input label="Customer Category">
        <inputExpression id="inputExpression_1" typeRef="string">
          <text>customerCat</text>
        </inputExpression>
        <inputValues>
          <text>"BRONZE","SILVER","GOLD"</text>
        </inputValues>
      </input>
      <output id="outputExpression_2" label="Discount Percentage"
              name="discountPerc" typeRef="number">
        <outputValues>
          <text>"0","5","10","20"</text>
        </outputValues>
      </output>
      <rule>
        <inputEntry id="ie_1_1"><text><![CDATA[== "BRONZE"]]></text></inputEntry>
        <outputEntry id="oe_2_1"><text><![CDATA[5]]></text></outputEntry>
      </rule>
      <rule>
        <inputEntry id="ie_1_2"><text><![CDATA[== "SILVER"]]></text></inputEntry>
        <outputEntry id="oe_2_2"><text><![CDATA[10]]></text></outputEntry>
      </rule>
      <rule>
        <inputEntry id="ie_1_3"><text><![CDATA[== "GOLD"]]></text></inputEntry>
        <outputEntry id="oe_2_3"><text><![CDATA[20]]></text></outputEntry>
      </rule>
      <rule>
        <inputEntry id="ie_1_4"><text><![CDATA[-]]></text></inputEntry>
        <outputEntry id="oe_2_4"><text><![CDATA[0]]></text></outputEntry>
      </rule>
    </decisionTable>
  </decision>
</definitions>
```

要点：

- 最后一行 `-` 是**兜底规则**（代替未实现的 default output），任何未分类客户返回 0。
- `hitPolicy="UNIQUE"` 保证规则互斥；若改用 `FIRST` 可把兜底行放末尾实现"特例优先"。
- 输出条目可引用流程变量做动态拼装，如 `<text><![CDATA[${payload.user}]]></text>`，输出 JSON 片段给下游使用。

## 23. 支持边界与最佳实践

1. **Flowable DMN 是"决策表引擎"而非完整 DMN 实现**：无 FEEL、无 DRG、无字面表达式。复杂规则链用 BPMN 编排多个 Decision Task；需要完整 FEEL/DRG 的场景考虑 Camunda DMN 或 Drools。
2. **规则与流程分离部署**：决策表独立版本化，业务改折扣规则只需重新部署 `.dmn`，无需发版流程定义——这是引入 DMN 的核心收益。
3. **开启执行审计**：生产环境 `history-enabled: true`，每次决策的输入输出可追溯，满足风控/定价类场景的合规要求。
4. **兜底规则常备**：用 `-` 规则保证表完备（complete），避免空结果进入下游分支判断。
5. **命中策略选择口诀**：互斥规则用 UNIQUE；特例+兜底用 FIRST；取最严重结果用 PRIORITY；累计型结果（总折扣、命中条数）用 COLLECT + 聚合符。
6. **性能**：单张大表优于大量小表频繁调用；决策定义有缓存，同一定义的重复执行开销很低。
7. **版本陷阱**：COLLECT `#` 计数默认 DMN 1.2 语义（不去重，去重需 Decision 元素的 `forceDMN11` 标志）且返回 Double；严格模式 `strict-mode`（默认开启）下 UNIQUE/ANY/PRIORITY/OUTPUT ORDER 违规抛 FlowableException，测试时建议保持严格。

---

## 附录：全文版本与依赖速查

| 项 | 值 |
|---|---|
| BPMN 标准 | 2.0 |
| DMN 支持 | 决策表 + 7 种命中策略（DMN 1.1 XML，COLLECT `#` 默认 1.2 语义） |
| Flowable 版本 | 8.0.x |
| Java 基线 | 17+ |
| Spring Boot / Framework | 4.x / 7.x |
| 自动部署目录 | BPMN → `resources/processes/`（`*.bpmn20.xml`）；DMN → `resources/dmn/`（`*.dmn`） |
| 关键表族 | `ACT_RU_*` 运行、`ACT_HI_*` 历史、`ACT_RE_*` 流程定义、`ACT_DMN_*` 决策（含 `ACT_DMN_HI_*` 决策审计） |
