# 基于 Flowable 的业务系统：架构模式、设计模式与实现模式全景解析

*发布日期：2026-09-23 | 面向读者：架构师与高级开发工程师*

## 0. 引言

### 0.1 为什么"模式"比"功能"更重要

#### 0.1.1 自由度极高的引擎，需要经过验证的取舍

Flowable 的功能清单长得令人安心：BPMN 2.0 全量执行语义、CMMN、DMN、事件注册引擎一应俱全[^1^]，从 6.x 到 2026 年 2 月发布的 8.0.0（Spring Boot 4 / Spring 7 基线）持续演进[^2^]。但功能多不等于系统好建。Flowable 的定位是一个嵌入式引擎内核[^3^]，它几乎不替你做任何架构决定：引擎放进业务服务还是独立部署，审批数据留在引擎表还是业务库，一致性靠本地事务还是 Outbox——每个问题都有十种实现路径，且每条路径都能在官方文档里找到支持依据。

**自由度高的引擎，最怕没有纪律的使用。** 同一个"请假审批"需求，有人直接在 ServiceTask 里写 HTTP 调用，有人用全局监听器推 MQ，有人把业务字段全塞进流程变量——三者都能跑，三年后的维护成本天差地别。模式（Pattern）的价值正在于此：它不是功能清单的重新排列，而是他人在真实约束下验证过的取舍记录，告诉你哪条路在哪种约束下不后悔。

#### 0.1.2 三个层次的模式地图

本文把 Flowable 实践中的模式分为三个层次。**架构模式**回答系统形态问题：引擎放在哪个进程里、流程定义归哪个团队管、数据怎么分布；**设计模式**回答代码组织问题：业务模块如何与引擎解耦、审批人规则如何扩展、事件如何订阅；**实现模式**回答引擎机制组合问题：会签怎么建模、驳回怎么实现、超时如何催办。三个层次自外向内依次落地，但决策顺序应当相反——先定架构，再谈封装，最后才是机制拼装。本章处理第一层。

## 1. 架构模式：引擎放在哪、流程归谁管

架构层的第一个问题永远是部署形态。它决定了你能否用本地事务、待办列表怎么查、引擎升级伤不伤业务——后续所有设计与实现模式都是这个选择的推论。

### 1.1 嵌入式引擎：与业务同 JVM 的事务红利

#### 1.1.1 共用 DataSource 与事务管理器，流程与业务一个本地事务

嵌入式是 Flowable 的经典形态：引擎作为依赖引入 Spring Boot 应用，与业务代码同 JVM、同数据源。这个形态最大的红利是**本地事务一致性**。Flowable 与 Spring 集成时，引擎内部用 `TransactionAwareDataSourceProxy` 包装传入的 DataSource，确保引擎获取的连接参与当前 Spring 事务[^4^][^5^]；当业务表与引擎表共用同一 DataSource 和事务管理器时，"插入业务审批单"和"完成任务推进流程"可以放进同一个本地事务，失败一起回滚，无需 XA/JTA[^6^]。社区实践给出的固定执行顺序也建立在这一前提上：先写业务记录再调用 complete，因为 complete 之后运行时任务随即消失且可能同步触发监听器[^7^]。

这个红利是真实的，也是独占的。Camunda 官方在对比嵌入式与远程引擎时明确指出：架构上唯一的本质差异就是**远程引擎无法与业务代码共享技术 ACID 事务**，一致性必须另行处理[^8^]。

#### 1.1.2 代价：引擎升级与业务耦合、多业务线无法复用

红利背后是三重耦合。其一，引擎版本绑定业务应用的发版节奏，升级引擎意味着回归所有内嵌它的服务；其二，每个内嵌引擎的服务各自持有流程数据，**没有统一数据源查看某用户的全部待办**，跨业务线要组合查询[^9^]；其三，流程模型散落在各业务仓库里，治理无从谈起。行业风向也在移动：Camunda 曾长期推荐嵌入式，2022 年起官方立场转向远程引擎，其 Camunda 8 内核 Zeebe 甚至完全不支持嵌入[^8^]。Flowable 仍坚持"可嵌入也可独立服务"的双形态[^3^]，选择权留给了你——这正是需要模式的地方。

### 1.2 独立流程服务与"流程中台"

#### 1.2.1 统一引擎+统一待办中心+多业务接入：国内实践形态

把引擎从业务服务里拿出来独立部署，经 REST/gRPC 对外提供流程能力，就得到了另一种形态。它在国内有一个更具本土色彩的变体——**流程中台**：统一引擎承载所有业务系统的审批流，统一待办中心汇集各系统待办一站式处理[^10^]。这条路线有两条实现分支：重的分支是引擎本身集中，所有业务系统远程接入；轻的分支是引擎仍分散在各系统，只在前端做一个对接各系统待办接口的**聚合展现层**[^11^]。社区评审记录显示，直接独立部署 flowable-rest 走 REST 接入的团队是少数——统一视图与统一审批管理的收益明确，但初期的技术门槛让不少团队退回内嵌方案[^9^]。

#### 1.2.2 代价：跨服务一致性需 Outbox/最终一致

独立部署的代价同样清晰：**跨服务一致性失去了本地事务这个拐杖**。业务服务"更新自己的库"和"通知引擎推进流程"是两个系统的两次写入，业界标准解法不是强行用 XA 包住两边，而是 Transactional Outbox——事件记录与业务数据在同一本地事务写入本库 Outbox 表，再由独立 relay 转发到消息代理，配合消费端幂等实现最终一致[^12^][^13^]。也就是说，选择独立流程服务等于同时签收了一整套最终一致性基础设施。

三种形态各有适配面，对比如下：

| 维度 | 嵌入式引擎 | 独立流程服务/流程中台 | 混合式（嵌入式+共享库或分片） |
|---|---|---|---|
| 一致性 | 本地事务强一致[^6^] | 需 Outbox+幂等，最终一致[^12^] | 视拓扑而定 |
| 统一待办 | 无，需跨服务组合查询[^9^] | 天然统一[^10^] | 共享库时可统一查询 |
| 引擎升级 | 与业务同发版，耦合紧 | 独立演进，业务无感 | 部分耦合 |
| 运维成本 | 随业务服务分摊 | 中心组件可用性要求高[^14^] | 最高 |
| 适用规模 | 单一业务线/单体 | 多业务线、流程治理诉求强 | 超大规模分片场景 |

这张表的核心读法：**没有一行是全绿**。嵌入式的每一行优势（一致性、运维）都精确对应中台的劣势，反之亦然——这正是"形态选择是取舍而非优劣"的直接证据。混合式看似两头兼顾，实际把两套运维复杂度都收了下来，只应在确有分片规模压力时采用。Camunda 官方给出的经验法则值得记住：引擎放在哪里不是最重要的问题，**流程模型的所有权与治理归属才是**[^14^]——形态服务于治理，而非相反。

### 1.3 微服务语境：编排 vs 协同

当 Flowable 进入微服务架构，它扮演的角色卷入了一场持续多年的方法论争论：跨服务协作应该用编排（Orchestration）还是协同（Choreography）？

#### 1.3.1 Orchestration vs Choreography 定义与权衡（Fowler/Richardson/Camunda 出处）

**协同**让各服务独立响应事件，没有谁对整体结果负责；**编排**由中心引擎持有流程状态、排序服务调用并从失败中恢复[^15^]。协同的吸引力在于低耦合与简单，但 Fowler 早在 2017 年就指出其结构性缺陷：当业务流程横跨多个事件时，**整体流程不显式存在于任何程序文本中**，只能靠监控运行中的系统去还原，很容易在不知不觉间失去对大尺度流程的可见性[^16^]。Richardson 在《Microservices Patterns》中列举的协同式 Saga 缺点与此呼应：更难理解、可能出现服务间循环依赖、每个服务须订阅影响自身的所有事件反而造成紧耦合——因此他建议复杂场景优先编排式 Saga[^17^]。

编排的代价同样真实：编排器有变成"smart orchestrator, dumb service"反模式的风险，中心组件引入潜在单点[^17^]。Flowable 官方的立场是两者共存：简单交互保持事件驱动，需要特定顺序、特定结果或业务规则约束的工作交给编排——编排施加在流程层，不侵入服务自治[^18^]。Camunda 的表述更直白：**多数生产系统是两者混合**，事件总线与编排引擎并存，不必 upfront 二选一[^15^]。

#### 1.3.2 Saga 模式与流程引擎：编排式 Saga 的天然载体

跨服务一致性问题的理论原型是 Saga：Garcia-Molina 与 Salem 在 1987 年 SIGMOD 论文《Sagas》中提出，长事务可被写成可与其他事务交错的事务序列，系统保证要么全部成功，要么逆序执行补偿事务修正部分执行[^19^]。Richardson 把它映射到微服务：跨服务业务事务 = 一串本地事务，每个本地事务更新库并发布事件触发下一个，失败则执行补偿[^20^]。补偿不是数据库回滚——扣款发生了就是发生了，补偿是一笔新的退款业务操作，可能有自身副作用，且**必须幂等**[^21^]；Saga 同时放弃了 ACID 的隔离性，并发 Saga 可能造成数据异常，需语义锁等对策[^20^]。

流程引擎是编排式 Saga 的天然载体：BPMN 内建补偿事件，引擎负责按正确顺序可靠执行补偿活动[^22^]。Rücker 的分布式事务三策略总结了选择空间：微服务间用不了技术 ACID 事务，业务层一致性只有三条路——有意识地忽略（Ignorance）、事后道歉式修正（Apologies）、或者 Saga 业务级回滚[^22^]。用引擎编排 Saga，本质是把第三条路的补偿逻辑从散落的代码搬进可审计的流程模型。

#### 1.3.3 引擎部署形态之争：Camunda"每服务一引擎" vs 国内流程中台集中化——条件化结论

更微妙的问题是引擎本身放在哪。Camunda 官方主张**去中心化为默认**：每个需要引擎的微服务配一个引擎，符合微服务自治与隔离的价值观，公司越大越应如此[^14^]；Rücker 在 SE Radio 访谈中进一步推荐联邦式模型——每个微服务配对自己的工作流处理重试与失败模式[^23^]。Zalando 是这一路线的标志性案例：订单数据按客户 email 分片到 8 个结构相同的库，**每个分片部署一个引擎**，支撑 1600 万+活跃客户、同步处理小于 300ms[^24^]。

国内实践则给出了相反方向的答案：流程中台主张集中——统一引擎、统一待办、统一治理[^10^]。这不是对错之争，而是约束不同：去中心化派优化的目标是**服务自治与故障隔离**，代价是每个团队自行运维引擎、无开箱即用中央监控[^14^]；中台派优化的目标是**流程治理与用户体验一致性**，代价是中心组件可用性要求与跨服务最终一致[^12^]。条件化结论是：**组织规模大、团队自治成熟、流程即服务内部逻辑时，倾向每服务一引擎；流程以人工审批为主、需要统一待办与统一监管口径时，倾向集中式中台**。争议可以搁置，前提只有一个——无论引擎在哪，流程模型的所有权必须明确到团队[^14^]。

### 1.4 数据架构模式

#### 1.4.1 流程数据与业务数据分离，businessKey 关联（引擎不保证唯一须自建索引）

架构层的数据纪律只有一条主线：**流程引擎管流转，业务库管数据**，两者用 businessKey 关联。官方 Javadoc 明确"提供 businessKey 是最佳实践"，用于把流程实例绑定到有业务含义的标识（如申请编号），且运行时与历史表均有对应列与索引，查询性能优于按变量检索[^25^]。社区通行做法是双向关联：流程侧存 businessKey，业务表增加 `process_instance_id` 字段，发起时在同一事务内先插业务数据、启动流程、回写实例 ID[^26^]。

这里有一个必须写进架构文档的事实：**引擎不保证 businessKey 唯一**。唯一约束早在 ACT-1860（2013 年）就已移除，同一 businessKey 可以启动多个实例[^27^]。因此幂等防重必须自建：在 (PROC_DEF_ID_, BUSINESS_KEY_) 上加唯一索引，或建立业务绑定表对 (business_type, business_key) 加唯一约束作为业务侧幂等边界[^28^]。这条约束直接影响接口设计——客户端重试、双击提交、消息重投都会制造重复启动请求，架构上不能让"不重复"依赖调用方自觉。

#### 1.4.2 CQRS 读模型：待办列表不打历史表

查询侧的模式可以概括为一句话：**待办查运行时表，已办查历史表，统一待办自建读模型**。待办的数据源是运行时任务表，已办与我发起查历史表；"在办""抄送我"不是引擎标准口径，须先定义产品语义再落表[^29^]。官方文档也建议把查询导向历史表以减轻运行时表压力——运行时表设计为实例结束即清空，保持小而快[^30^]。

真正的坑在统一待办。流程中台形态下，直接 JOIN 引擎表给全公司出待办列表是不可持续的：同一条流程重复出现、转办后已办找不到、候选组任务漏掉，是一线反复踩过的坑[^29^]。社区实践的解法是 CQRS 式读模型：自建待办表，借全局事件监听器在任务创建/完成时同步写入，引擎侧写、业务侧读[^31^][^32^]。其架构含义在于：待办列表是**读模型**而非**查询结果**，它应当按产品口径（而不是引擎表结构）建模，写入靠事件同步，与引擎存储彻底解耦。至于监听器的事务语义与容错红线，属于设计模式的范畴，下一章 2.3 节展开。

### 1.5 架构决策速查（表）

| 你的场景 | 推荐形态 | 关键机制 | 首要风险 |
|---|---|---|---|
| 单体业务系统，审批内嵌于业务 | 嵌入式引擎 | 同库同事务管理器，本地事务一致[^6^] | 引擎升级与业务耦合 |
| 多业务线，需统一待办与流程治理 | 独立流程服务/流程中台 | Outbox+消费端幂等[^12^]；自建待办读模型[^31^] | 中心组件可用性；一致性复杂度 |
| 微服务跨服务事务（下单/履约） | 编排式 Saga，引擎归属服务团队 | BPMN 补偿事件；补偿须幂等[^21^] | 编排器吞噬领域逻辑[^17^] |
| 简单服务联动、无严格顺序约束 | 事件协同，引擎只跟踪不指挥 | 消息/事件总线；引擎做只读跟踪起步[^33^] | 大尺度流程失去可见性[^16^] |
| 超大规模高吞吐（百万级日单量） | 分片部署，每分片一引擎 | 按业务键分库分片（Zalando 范式）[^24^] | 运维复杂度最高 |

速查表的用法是**排除法**而非对号入座：先确认哪些行与你的约束冲突，再在剩余选项里比代价。需要特别强调两个反直觉点。其一，"微服务架构"本身不构成独立流程服务的理由——Camunda 的官方默认恰恰是每个微服务一个嵌入式引擎[^14^]，拆分引擎的正当理由只有治理诉求或伸缩诉求。其二，无论选哪一行，有三件事是共同的：businessKey 的唯一性要自己守[^27^]、跨服务一致性要用 Outbox 而不是假装有大事务[^7^]、待办读模型要按产品口径自建[^29^]。这三条不因形态而变，是所有架构决策的公共底座。架构形态定了之后，下一个问题是代码如何组织——业务模块凭什么不感知引擎的存在，这正是下一章设计模式要回答的。

## 2. 设计模式：代码怎么组织

第一章解决的是"引擎放在哪"的形态问题；形态定了，接下来是每个团队都绕不开的问题：业务代码以什么姿势触碰引擎。Flowable 的 API 自由度极高——七大服务、上百个方法直接摆在面前[^34^]，不加组织地调用，半年后引擎细节就会渗进每一个业务 Service。本章把五个经典 GoF 模式落到 Flowable 的具体机制上，回答代码该怎么分层、扩展点该开在哪。

### 2.1 门面模式：业务零感知 Flowable

门面模式（Facade）的意图是为一组复杂子系统提供统一的高层接口。在 Flowable 语境下，它要封装的"子系统"就是 RuntimeService、TaskService、HistoryService 这一整族引擎 API。理由很现实：业务方关心的从来不是"启动流程实例"，而是"提交一张请假单"；不是 `taskService.complete`，而是"同意"或"驳回"。把引擎语义直译给业务方，等于让每个业务开发者都去学一遍 BPMN。

国内开源脚手架已经把这个模式做成了事实标准。yudao（ruoyi-vue-pro）用 `BpmProcessInstanceApi#createProcessInstance(...)` 这类模块级 API 封装引擎调用，官方文档明确其目标是让业务模块"无需关心底层是 Activiti 还是 Flowable 引擎，甚至未来可能的 Camunda 引擎"[^35^]；其工作流模块采用 api/biz 分离结构，对外只暴露接口定义，引擎相关实现收敛在 biz 层的 framework 包内[^36^]。这套结构的价值不在"能换引擎"——换引擎是小概率事件——而在于语义收敛：`submit / approve / reject` 是业务词汇，引擎 API 的演进、驳回跳转这类引擎特有机制（实现篇详述）都被关在门面之后，升级引擎时业务代码零改动。

门面层还要顺手解决一个关联问题：流程实例与业务单据如何互相找到对方。通行做法是双向关联——流程实例以 businessKey 持有业务主键，业务表增加 process_instance_id 字段回存实例 ID[^26^]。但要警惕一个常见误解：businessKey 的唯一性约束早在 ACT-1860 中被移除，幂等须自建索引（机制见 1.4.1）[^27^]，门面层还要再叠一道业务绑定表——社区实践建议建 `biz_workflow_binding` 绑定表，对 `(business_type, business_key)` 加唯一约束，作为业务侧防重复提交的幂等边界[^28^]。这是门面模式的隐藏收益：所有"引擎不管但业务必须管"的约束，都有了一个统一的安放处。

### 2.2 策略模式：审批人与业务路由

策略模式（Strategy）把一族可互换的算法封装在共同接口之后。审批系统里最典型的"可互换算法"就是审批人计算：按角色、按部门负责人、按岗位、按发起人自选……规则层出不穷，且每个客户都要加自己的那一种。

Flowable 官方给出的落点是在任务 create 事件的 TaskListener 中通过 DelegateTask 动态设置办理人与候选人/候选组[^37^]，或直接用表达式求值。但官方机制只解决"在哪挂钩"，不解决"如何组织规则"。yudao 的三层结构是迄今最完整的参考实现：自定义 `BpmUserTaskActivityBehavior` 接管用户任务行为，`BpmTaskCandidateInvoker` 负责动态计算，具体的 `BpmTaskCandidateStrategy` 是 SPI——每种审批人规则一个实现 Bean，核心是 `calculateUsers(DelegateExecution execution, String param)` 一个方法，内置角色、部门成员、部门负责人、岗位等七八种规则，新增策略只需实现接口注册 Bean，引擎与流程模板都不用动[^38^]。以下为简化示意（非 yudao 原签名）：

```java
public interface ApprovalAssigneeStrategy {
    String type();                                    // 规则类型标识
    List<String> calculateUsers(String processKey,    // 流程/业务上下文
                                String nodeId, String param);
}

@Component
public class DeptLeaderStrategy implements ApprovalAssigneeStrategy {
    public String type() { return "dept_leader"; }
    public List<String> calculateUsers(String processKey, String nodeId, String param) {
        // 按发起人部门找负责人；算不出来时返回兜底人，而不是让流程断在节点上
        return List.of(deptService.findLeader(param).orElse(adminId()));
    }
}
```

这段代码的两个细节都是踩坑换来的：其一，候选人计算必须绕过数据权限过滤，否则"部门负责人"可能被权限体系滤掉导致流程悬空；其二，审批人算为空时必须有兜底策略（转管理员或自动通过）[^38^]。同一套策略思想也适用于业务路由：多套审批流共用一张 BPMN 模板时，把"按金额/业务类型分流"的差异收敛到网关表达式背后的策略 Bean，而不是为每种业务复制一张流程图。

### 2.3 观察者模式：全局事件监听

观察者模式（Observer）让对象状态变化自动通知订阅者。Flowable 把这件事做成了一等公民：`FlowableEventListener` 全局事件机制覆盖 TASK_CREATED、TASK_COMPLETED、PROCESS_COMPLETED、PROCESS_CANCELLED 等全量生命周期事件，监听器可通过引擎配置 `eventListeners`/`typedEventListeners`、运行时 API 或 BPMN XML 三种方式注册[^39^][^40^]——注意运行时注册的监听器在引擎重启后丢失，生产上应走配置化注册[^40^]。

全局监听器的经典用途是横切逻辑：任务创建时写自建待办表、任务完成时更新状态并通知下一节点审批人、流程结束时回写业务单据状态[^32^]。yudao 甚至把"流程结束回调"再封装成抽象类 `BpmProcessInstanceResultEventListener`，业务方继承它即可收到最终的通过/不通过结果[^41^]。这是观察者之上再叠一层门面，方向完全一致：业务订阅的是业务事件，不是引擎事件。

但全局监听器有两条官方红线，违反任意一条都是生产事故：

1. **`isFailOnException=true` 会回滚引擎事务**。监听器与引擎命令在同一事务内执行，返回 true 时异常上抛、当前命令失败、整个事务回滚——也就是说一个发短信失败的监听器可以把一次正常的审批操作拖垮。官方原文的建议是：非业务关键逻辑必须返回 false[^40^]。
2. **`onTransaction=COMMITTED` 才是发通知的正确时机**。通过 `isFireOnTransactionLifecycleEvent()=true` 配合 `getOnTransaction()=COMMITTED`，监听器在事务提交后才触发，避免"事务未提交就发 MQ/通知"造成的脏消息[^42^][^43^]。TaskListener 同样支持 `onTransaction="committed"` 属性[^42^]。

另有一个集群部署的隐性边界：监听器只接收所注册引擎实例派发的事件，多引擎共库部署时事件不会跨引擎广播，每个节点都要注册，或者干脆改走外部事件通道[^40^]。外加一条线程安全纪律：监听器实例在部署期创建、跨所有流程实例共享，不得用成员字段保存状态[^44^]。

```java
@Component
public class TodoListSyncListener extends AbstractFlowableEngineEventListener {
    @Override
    public boolean isFailOnException() { return false; }          // 红线一：不拖垮引擎事务
    @Override
    public boolean isFireOnTransactionLifecycleEvent() { return true; }
    @Override
    public String getOnTransaction() { return TransactionState.COMMITTED.name(); } // 红线二：提交后触发

    @Override
    protected void taskCreated(FlowableEngineEntityEvent event) {
        todoListService.insertFrom((TaskEntity) event.getEntity()); // 写自建待办读模型
    }
}
```

### 2.4 适配器模式：外部系统隔离层

适配器模式（Adapter）把不兼容的接口翻译成目标接口。流程编排天然要调外部系统——ERP 下推、短信网关、企业微信、征信接口——如果让 ServiceTask 里的 JavaDelegate 直接 `RestTemplate` 调三方，超时策略、熔断降级、报文留痕就会散落在每个 Delegate 里，且引擎事务会被远程调用长时间占住。

正确的组织方式是所有 ServiceTask 只调用本系统的 Adapter 层：Adapter 统一封装超时、重试、熔断与调用留痕，把外部系统的协议差异挡在引擎之外。社区实践给出的理由是事务性的——"流程引擎与消息供应商不能处在一个长事务里"，审批事务提交时只写 Outbox，由消费者异步发送 MQ/短信/邮件，这样短信超时或企业微信限流都不会锁住 Flowable 的运行时表[^45^]。换句话说，适配器模式在这里不只是接口翻译，更是一道事务边界：引擎管流程状态，Adapter 管外部世界，两者之间用 Outbox 衔接，失败按策略重试、最终失败进死信人工处理[^45^]。把引擎当 ESB 用（在流程里直连所有系统），是第四章反模式清单里要专门批驳的做法。

### 2.5 模板方法与 Spring 集成

模板方法模式（Template Method）在基类中固定算法骨架、把差异步骤留给子类。它在 Flowable 项目里的体现不在引擎内部，而在"审批流程基类"的沉淀上：发起校验→写业务单据→启动流程→回写实例 ID→注册结果回调，这个骨架对请假、报销、合同审批完全一样，差异只在校验规则与结果处理。定义一个抽象基类固定骨架，子类实现 `validate()`、`onApproved()`、`onRejected()` 钩子，配合 2.3 节的结果事件订阅，新接入一种审批业务的成本就只剩两个钩子方法。

模板方法要生效，前提是监听器与委托类能被 Spring 容器管理——否则基类里注入不了业务服务。Flowable 的 Spring Boot 集成正是为此设计：引入 `flowable-spring-boot-starter` 即可自动装配引擎[^46^]；BPMN 中用 `flowable:delegateExpression="${beanName}"` 绑定 ServiceTask 与监听器，委托对象解析为 Spring Bean，天然获得依赖注入与代理能力[^37^]；需要更深定制时，暴露一个 `EngineConfigurationConfigurer<SpringProcessEngineConfiguration>` Bean，会在引擎完全创建前被回调，全局监听器、自定义行为都可在此注入[^46^]。需要提醒的是委托实例的生命周期：以 class 方式绑定的委托类在部署期创建并跨流程实例共享，`delegateExpression` 绑定同样要满足线程安全约束[^44^]——模板基类若持有可变成员状态，踩的就是这个坑。

### 2.6 设计模式映射总表

| 模式 | Flowable 落点 | 主要收益 | 关键约束 |
|---|---|---|---|
| 门面 | RuntimeService/TaskService 封装为语义化模块 API（yudao api/biz 结构）[^35^] | 业务零感知引擎；驳回/跳转等机制关在门后 | businessKey 不唯一，幂等须门面层自建[^27^] |
| 策略 | 审批人 SPI：create 事件 TaskListener / 自定义 ActivityBehavior + Strategy Bean[^37^][^38^] | 规则可插拔，多业务共用流程模板 | 候选人计算避开数据权限；为空须兜底[^38^] |
| 观察者 | FlowableEventListener 全局事件[^39^] | 待办同步、通知、审计等横切逻辑统一收口 | isFailOnException=false；COMMITTED 后触发[^40^][^43^] |
| 适配器 | ServiceTask 只调内部 Adapter，外部交互走 Outbox[^45^] | 统一超时/熔断/留痕；引擎事务不被远程调用占住 | 引擎不是 ESB；消费端须幂等[^45^] |
| 模板方法 | 审批流程基类 + delegateExpression Bean 化[^37^] | 新业务接入只剩钩子方法 | 委托/监听器实例共享，须线程安全[^44^] |

这张表值得再看一眼，因为它揭示了一个比单个模式更重要的规律：五个模式的收益方向是一致的——都在把"引擎的自由度"兑换成"业务的确定性"。门面挡住 API 细节，策略挡住规则差异，观察者挡住横切耦合，适配器挡住外部世界，模板方法挡住重复骨架。反过来也成立：凡是没有被这五个位置收编的引擎调用（业务 Service 里直接 `runtimeService` 翻历史表、JavaDelegate 里直连三方接口），几乎都是后期重构的债主。设计模式篇的使命到此为止；至于驳回怎么跳、加签怎么加、会签怎么计票这些"引擎机制怎么组合"的问题，交给实现篇。

## 3. 实现模式：引擎机制怎么组合

第 2 章解决的是代码组织问题——门面、策略、观察者把业务代码与引擎 API 隔开。本章再往下沉一层：门面之内，引擎机制本身怎么组合。会签、驳回、催办这类诉求，BPMN 图里没有现成符号，Flowable API 里也没有同名方法，它们都是多实例、状态迁移、定时事件等底层原语的特定组合。实现模式研究的就是这些组合的惯用法与边界。

### 3.1 从工作流模式经典体系看 BPMN 表达能力

讨论"BPMN 能不能表达某个业务诉求"之前，先有一个超越具体引擎的评价框架。Workflow Patterns 体系由埃因霍温理工大学（Wil van der Aalst）与昆士兰科技大学（Arthur ter Hofstede）于 1999 年联合发起，目标是为流程技术提供概念基础[^47^]。其 2003 年奠基论文《Workflow Patterns》归纳了 20 个控制流模式，用以对比 15 个商用工作流系统，结论是各系统表达能力差异显著、整体不尽如人意[^48^][^49^]；2006 年修订版将控制流模式扩充到 43 个，并为每个模式给出 Coloured Petri-Net 形式化模型[^50^]。这套体系的评价语义值得记住："-" 评级不代表模式无法实现，只代表缺少直接支持、实现成本更高——任何图灵完备的语言都能实现任何模式，区别在于适宜性（suitability）[^51^]。这正是"实现模式"存在的理由：引擎不直接支持的诉求靠机制组合补上，代价与坑就是本章余下各节的内容。

下表选取与业务系统关系最密切的若干模式，给出它们在 BPMN 2.0/Flowable 中的落点：

| WCP 编号 | 模式 | BPMN/Flowable 落点 | 支持度 |
|---|---|---|---|
| WCP1 | Sequence 顺序 | 顺序流 | 直接 |
| WCP2/3 | Parallel Split / Synchronization | 并行网关 fork/join，join 等待全部入口到达[^52^] | 直接 |
| WCP10 | Arbitrary Cycles 任意循环 | 图结构天然支持多入口多出口循环，块结构语言（BPEL）无法表达[^53^] | 直接 |
| WCP12–14 | 多实例（无同步/设计期已知基数/运行期已知基数） | `multiInstanceLoopCharacteristics`，相当于 for each[^37^] | 直接 |
| WCP15 | 无运行时先验知识的多实例 | 实例数须在进入活动时确定，运行中追加须用加签 API 变通[^54^] [^55^] | 变通 |
| WCP16 | Deferred Choice 延迟选择 | 事件网关：每条出口创建事件订阅，先触发者胜出[^56^][^37^] | 直接 |
| WCP25 | Cancel Region 取消区域 | 中断边界事件/事件子流程，局限是取消区域须为连通子图[^57^] | 部分 |

这张表传达两个判断。其一，BPMN 对控制流视角的覆盖相当好——2006 年 Wohed 等人的系统性评估确认 BPMN 直接支持多数控制流模式，短板集中在无先验多实例、Milestone 与资源视角（泳道不等于资源模型）[^58^]；这意味着审批系统的控制流诉求大多能落在图上，但"把人组织起来"的诉求（职责分离、委派升级）需要引擎扩展或平台层补足。其二，映射表本身就是建模自检清单：当你在图上表达不出某个模式时，先问是 BPMN 的短板还是自己没选对构造，再决定是换建模方式还是动用 API 变通。

表达能力之外是粒度纪律。Mendling 等人的 7PMG（Seven Process Modeling Guidelines）给出了有实证支持的量化规则，最常被引用的是 G7：模型超过 50 个元素应分解[^59^]。命名上，Camunda 官方最佳实践与 Bruce Silver 的 Method and Style 一致：活动用动词+宾语，排他网关用问题命名、出口用答案命名[^60^]。另一条易违反的纪律：不要在图里建模重试——重试是执行层的关注点，交给异步执行器（见 3.7），画进图里只会污染业务语义[^61^]。

### 3.2 多人协作：会签/或签/按比例

"会签""或签"是国内审批场景的通行术语，但 BPMN 2.0 与 Flowable 文档中都没有这两个概念[^62^]——它们的引擎落点是多实例活动（multi-instance）：对一个集合中的每个元素各生成一个任务实例，并行或串行执行，引擎暴露 `nrOfInstances`、`nrOfActiveInstances`、`nrOfCompletedInstances`、`loopCounter` 四个内置计数变量[^37^]。`completionCondition` 表达式在每个实例结束时求值，为 true 即销毁剩余实例、结束多实例活动；官方文档的示例本身就是"60% 完成即提前结束"的按比例写法[^37^]。社区的标准映射是：会签 `${nrOfCompletedInstances == nrOfInstances}`，或签 `${nrOfCompletedInstances >= 1}`，按比例 `${nrOfCompletedInstances/nrOfInstances >= 0.6}`[^63^][^64^]。

**这里埋着本章最重要的一个坑：内置变量统计的是"完成数"而非"同意数"。** 一个人投反对票后完成任务，同样计入 `nrOfCompletedInstances`——若业务语义的会签是"全员同意"，用默认写法就会让"有人拒绝"被算作"全员完成"。一票否决、按同意票数通过等规则，必须自定义计票：在 TaskListener 的 complete 事件中按审批结论累加 `approvedCount`/`rejectedCount` 变量，completionCondition 改为调用后端 Bean 判定[^55^]：

```xml
<userTask id="jointSign" name="会签审批" flowable:assignee="${assignee}">
  <multiInstanceLoopCharacteristics isSequential="false"
      flowable:collection="approverList" flowable:elementVariable="assignee">
    <!-- 计票逻辑收敛到 Bean，完成条件只问"是否已可结束" -->
    <completionCondition>${voteRule.shouldComplete(execution)}</completionCondition>
  </multiInstanceLoopCharacteristics>
  <extensionElements>
    <flowable:taskListener event="complete" delegateExpression="${voteListener}"/>
  </extensionElements>
</userTask>
```

```java
@Component("voteListener")
public class VoteListener implements TaskListener {
    public void notify(DelegateTask task) {
        boolean approved = (Boolean) task.getVariableLocal("approved");
        ExecutionEntity parent = (ExecutionEntity) task.getExecution().getParent();
        String key = approved ? "approvedCount" : "rejectedCount";
        int count = parent.getVariableLocal(key) != null ? (int) parent.getVariableLocal(key) : 0;
        parent.setVariableLocal(key, count + 1); // 完成条件/否决条件在 Bean 中读取这两个计数
    }
}
```

另外两个边界：其一，completionCondition 提前结束时剩余运行中的任务会被**删除**，审计上不能把未投票者记为同意[^37^]；其二，实例数在进入活动时一次性计算，事后修改 collection 变量不会生成新实例——运行中想加人，要走 3.4 节的加签 API[^55^]。

### 3.3 流程回退：驳回/撤回/任意跳转

驳回是中国式审批的刚需，也是让 BPMN 图失控的头号诱因——若把每个节点的回退路径都画成顺序流，图会迅速突破 7PMG 的 50 元素红线。正确的分工是：图上只画正常流转与业务级拒绝分支，任意回退交给运行时的状态变更原语。Flowable 的 `ChangeActivityStateBuilder` 提供 `moveActivityIdTo`、`moveExecutionsToSingleActivityId`、`moveActivityIdToParentActivityId` 等 move* 系列方法，支持跨子流程、多实例与并行网关的聚合/拆分迁移；需要纠偏的是，完整 move* API 自 **6.3.0**（2018-04）起可用，中文社区流传的"6.4.0 新增驳回"与源码不符——6.4.0 新增的是流程实例迁移[^65^][^66^]。

```java
// 并行分支整体驳回：收拢全部在途 execution，迁回发起节点
List<Execution> actives = runtimeService.createExecutionQuery()
        .processInstanceId(piId).activityIds("riskReview", "complianceCheck").list();
runtimeService.createChangeActivityStateBuilder()
        .processInstanceId(piId)
        .moveExecutionsToSingleActivityId(
                actives.stream().map(Execution::getId).collect(Collectors.toList()),
                "applyForm")
        .changeState();
// 平台层职责：清理历史任务残留与作废变量，业务侧作废单据需另行补偿
```

引擎提供的是通用原语而非"驳回服务"，三类坑必须在平台层消化。**第一，引擎不校验目标可达性**：官方 Javadoc 明确迁移可指向定义中任意活动，哪怕互斥分支甚至孤立节点，核心开发者也承认校验有待加强[^67^]——目标节点白名单须自行维护。**第二，并行网关必须整体收拢**：只退一个分支会产生垃圾数据、重走并行时 join 不再等待，须如上面的代码所示收拢全部在途 execution[^68^]。**第三，CallActivity 子流程跳回父流程会删除子流程历史记录**：`DefaultDynamicStateManager` 硬编码了 `deleteHistory` 参数，审计敏感场景须重写该 Bean，且与"子流程变量不输出映射"构成两难[^69^]。每次 changeState 都会重建 execution（executionId 变化），轨迹须靠 BpmnModel 沿顺序流回溯[^70^]——社区主流方案即归纳为"查目标、迁状态、清残留"三步[^71^][^72^]；驳回、撤回、挂起、终止语义不同，不应共用一个入口[^73^]。

### 3.4 任务变更：加签/转办/委派

三个词对应三套机制，混用是高频错误。**转办**是 `TaskService.setAssignee`：直接改派，不校验用户存在，任务不回流——办完即止，与原办理人再无关系[^74^]。**委派**是 `delegateTask` + `resolveTask`：委派后 assignee 变为被委派人、`delegationState` 置 PENDING、原办理人记入 owner；被委派人 `resolveTask` 后状态变 RESOLVED、任务**回到 owner**，由 owner 最终 complete[^74^][^75^]。委派的语义是"征询意见但决定权仍在委托人"，回流是它与转办的本质区别；被委派人的办结不等于任务完成，业务侧若按"任务完成"理解就会误判流程状态[^76^]。

**加签**没有 BPMN 标准概念，落点是 `RuntimeService.addMultiInstanceExecution` / `deleteMultiInstanceExecution`：向运行中的多实例父执行追加/删除实例[^25^]。边界有四：其一，API 只覆盖"当前会签节点加人"，前加签（先他人审再回到原办理人）与后加签（审完追加环节）表达不了，应建模为显式子轮次或可复用审批子流程[^55^]；其二，并行多实例加签立即生成任务，串行加签只创建执行排队等待，行为不同[^77^]；其三，`deleteMultiInstanceExecution` 的 `executionIsCompleted` 参数决定被删实例是否计入"完成数"，直接扰动会签计票的分母分子，必须按"撤销加签/人员离职"等原因区分[^25^]；其四，REST 模块没有对应端点，远程调用形态须自行封装[^78^]。工程上的推论是：只要节点有动态加人的可能，建模时就该建成多实例，哪怕初始只有一人。

### 3.5 时间与 SLA：催办/超时/升级

时间语义由边界定时事件承载，关键属性是 `cancelActivity`：默认 true（中断），定时器触发时原活动被销毁，适合"超时升级"——转上级、自动通过；置 false（非中断）则原活动保留、额外引出一条流，配合 `timeCycle`（ISO 8601 重复间隔如 `R3/PT10H`，或 cron）就是"多次催办"的标准形态[^37^]。时长可用表达式引用流程变量，实现按业务规则动态计算 SLA 阈值。

三条红线。**其一，`asyncExecutorActivate` 默认 false**：定时器只有在异步执行器启用时才会触发，生产环境必须显式开启（Spring Boot 下 `flowable.async-executor-activate=true`），否则全部催办/超时静默失效[^37^][^46^]。**其二，定时触发是 10 秒级精度**：定时作业持久化于数据库、重启不丢，但触发依赖 acquire 线程轮询抢占，存在默认 10 秒量级的延迟[^79^]，SLA 设计不应承诺"精确到秒触发"——此量级为基于抢占机制的推论（中等置信），以压测实测为准。**其三，边界事件只允许一条出口顺序流**，"催办+升级"双分支须先经并行网关中转，这是官方文档明示的已知限制[^37^]。

### 3.6 规则外置：DMN 决策表

当"谁审、审到哪一级、给什么额度"这类规则频繁调整时，把条件逻辑从网关表达式里抽出来、外置为 DMN 决策表，换来的是**改规则不改图**：决策表按 key 部署新版本即生效，默认解析最新版，无需重启或重发流程[^80^][^81^]。BPMN 侧通过 `<serviceTask flowable:type="dmn">` 加 `decisionTableReferenceKey` 引用决策表，单命中时输出变量直接写回流程变量、驱动后续网关；多命中时结果列表存入指定变量[^82^][^83^]。Flowable 支持 7 种 hit policy（FIRST/UNIQUE/ANY/PRIORITY 单命中，OUTPUT ORDER/RULE ORDER/COLLECT 多命中），评级矩阵宜用 UNIQUE 防规则重叠，审批路由宜用 FIRST 配兜底行[^84^]。

边界同样明确。**表达式语言是 JUEL 而非 FEEL**：DMN 规范定义的 (S)-FEEL 在 Flowable 中不被支持，从 Camunda 迁移规则写法时第一反应就会踩这个点[^84^]。开源版只支持 decision table 一种表达式类型，不支持嵌套 boxed expression 与 BKM（业务知识模型）[^85^]。社区对比的共识是：决策表适合"条件→结论"的可枚举规则，评分卡计算、规则链推理应留给 Drools 等专用引擎（其 DMN 实现达 conformance level 3 且支持完整 FEEL）[^86^][^87^]。另注意开源 7.x 起移除全部 UI 应用，DMN 建模需手写 XML 或用企业版 Flowable Design[^2^]。

### 3.7 可靠性组合：异步/重试/死信/补偿/Outbox/幂等

这是一组必须打包讨论的机制，因为它们共同回答一个问题：引擎动作与外部世界之间的一致性怎么保证。

引擎内部的可靠性由异步执行器提供。异步作业落库为 ACT_RU_JOB，多节点靠 lock owner + 锁租约防重，节点崩溃后过期作业由重置线程释放供他节点抢占——本质是数据库行级锁加租约，不是分布式锁中间件[^88^]。失败作业的生命周期是：async job 失败 → 转为带 due date 的 timer job 冷却 → 到期转回 async job 重试，默认 3 次耗尽后移入 ACT_RU_DEADLETTER_JOB 死信表，**不再自动执行**，须人工干预，可用 `moveDeadLetterJobToExecutableJob` 重放[^89^]。元素级可用 `flowable:failedJobRetryTimeCycle="R5/PT5M"` 定制退避；瞬时故障适合延迟重试，数据校验错误不宜重试，`R100/PT1S` 这类配置是打爆数据库与下游的典型反模式[^90^]。

跨服务层面，技术重试与业务补偿是正交的两层。Java 异常（超时、宕机）走上面的重试-死信链；业务拒绝（审批不通过）应建模为 BPMN Error 边界事件——BPMN Error 与 Java 异常"没有任何关系"，是业务异常的建模手段；而已完成步骤的回滚（退款≠撤销扣款）用补偿边界事件，补偿订阅在活动**成功完成后**注册[^37^]。这正是 Saga 模式的引擎实现（Saga 语义与出处见 1.3.2）[^19^]；补偿是业务级新操作而非存储层回滚，必须显式设计且幂等[^21^]。跨服务"更新数据库并发布消息"的原子性则靠 Transactional Outbox：审批事务提交时只写 Outbox 表，消费者异步投递 MQ 并重试，消息中间件的故障不会锁住引擎运行时表；社区实践给出的幂等键范式是 `taskId + ruleId + 时间窗 + 接收人 + 通道` 加唯一约束，消费端幂等是这套组合的硬前提[^45^][^20^]。

最后一处幂等盲区在引擎入口：businessKey 唯一约束已于 ACT-1860 移除、须自建索引（见 1.4.1）[^27^][^25^]，本节聚焦幂等启动与消费端幂等机制本身。落到机制上，防重复启动（如消息重复投递触发重复开流程）必须由业务绑定表唯一索引或先查后建承担，引擎不兜底；它与消费端幂等共同构成 Outbox 组合的闭环。

### 3.8 版本与灰度

Flowable 的版本语义简单但常被误解：同 key 重复部署版本号递增，definitionId 形如 `key:version:generatedId`；按 key 启动新实例默认用最新版本；**在途实例继续运行于其创建时的版本，部署新版不会自动切换**[^91^][^92^]。要迁移在途实例，官方正道是 6.4.0 引入的 `ProcessInstanceMigrationBuilder`（后演进为 ProcessMigrationService），支持 wait state、子流程与边界事件的映射迁移，且提供 `validateMigration()` 预校验；`setProcessDefinitionVersion` 只是继承自 Activiti 5 的内部 Command，未在 RuntimeService 暴露，勿当公共 API 使用[^93^][^94^]。

生产级升级的社区经验可归纳为"部署→切流→暂停"三步：先部署 v2 并验证；再通过业务绑定表把 definitionId 固化到业务配置，灰度按组织/租户等稳定维度选版本而非随机；确认配置收敛后才 `suspendProcessDefinitionById(oldDefinitionId, false, null)` 冻结旧版新建——顺序不能颠倒，先暂停会让仍持有旧配置的请求直接失败[^28^]。在途实例默认留在旧版跑完，显式迁移只在法规变更或严重缺陷时动用，因为迁移的映射成本与回归风险都不可忽略。

### 3.9 数据规范：变量轻量原则

流程变量的存储机制决定了它必须轻：每个变量在 ACT_RU_VARIABLE 占一行，string/date/json 存 TEXT_ 列（varchar(4000)）；超过 4000 字符的 String 自动转为 longString 类型、经 BYTEARRAY_ID_ 外键落入 ACT_GE_BYTEARRAY 的 BLOB——官方设计意图正是"大值不必每次都从库搬到应用"[^95^][^96^]。代价有三：其一，出于历史兼容，**默认任何一次 getVariable/setVariable 都会抓取并缓存该执行的全部变量**，大变量把每次变量访问放大成全表读[^97^]；其二，audit 级别变量持续同步进 ACT_HI_VARINST、full 级别每次更新都写 ACT_HI_DETAIL，大变量成倍放大历史写入[^30^]；其三，serializable 对象无法被查询索引，且存在类版本兼容风险。

结论是一条纪律：**变量只存标量与引用**——审批结论、路由标志、业务主键 ID；业务正文、影像、报文存业务库或对象存储，变量里只放引用 ID。确需在单一事务链内传递大对象时，用 transient variable（不持久化、无历史）[^97^]。这条原则与 1.4 节的"流程数据与业务数据分离"互为表里：businessKey 负责关联，变量负责路由，业务库负责状态。

### 3.10 实现模式速查

| 业务诉求 | 引擎机制 | 注意点 |
|---|---|---|
| 会签（全员同意） | 并行多实例 + completionCondition | 内置变量计完成数非同意数，须自定义计票 Bean [^55^] |
| 或签/按比例 | completionCondition `>=1` 或比例表达式 | 提前结束会删除剩余任务，未投≠同意 [^37^] |
| 驳回/任意跳转 | ChangeActivityStateBuilder（6.3.0+） | 并行须收拢全分支；CallActivity 跳回删子流程历史 [^68^][^69^] |
| 撤回（发起人收回） | 状态迁移回发起节点 + 清理脏数据 | 引擎无"撤回"原语，按驳回机制实现 [^71^] |
| 加签/减签 | add/deleteMultiInstanceExecution | 仅当前节点加人；串行加签不立即生成任务 [^77^] |
| 转办 | setAssignee | 不回流；与委派语义严格区分 [^74^] |
| 委派 | delegateTask + resolveTask | PENDING→RESOLVED 回流 owner，办结≠完成 [^75^] |
| 催办 | 非中断边界定时 + timeCycle | asyncExecutorActivate 默认 false 须显式开 [^46^] |
| 超时升级 | 中断边界定时（cancelActivity=true） | 触发为 10 秒级精度，勿承诺秒级 SLA [^79^] |
| 规则外置 | DMN 决策表 + DecisionTask | JUEL 非 FEEL；仅决策表，无 BKM [^84^][^85^] |
| 服务调用可靠性 | async=true + failedJobRetryTimeCycle + 死信 | 死信须人工重放；下游须幂等 [^89^] |
| 跨服务一致性 | Outbox + 幂等消费 + 补偿边界事件 | 补偿是业务操作须显式设计且幂等 [^20^][^21^] |
| 版本灰度 | 定义版本化 + 绑定表切流 + 挂起旧版 | 在途不自动切；顺序为部署→切流→暂停 [^28^] |
| 大数据传递 | 变量存 ID + transient variable | 超 4000 字符转 longString 落 ACT_GE_BYTEARRAY [^95^] |

这张表是本章的压缩形态，用法是反向查：接到诉求先定位行，再回看对应小节的边界讨论。表背后的共性更值得强调——Flowable 给出的几乎都是"机制"而非"功能"：会签是多实例加计票的组合，驳回是状态迁移加清理的组合，SLA 是定时事件加异步执行器的组合。引擎刻意不做业务封装，把语义裁决权留给平台层；这既是自由度，也是责任边界，平台层的封装质量直接决定这些组合是模式还是坑。

## 4. 反模式与选型建议

### 4.1 反模式清单

前面三章给出了正向的模式地图，本章换到反面。流程反模式的系统性编目早有工业文献奠基——Koehler 与 Vanhatalo 2007 年在 IBM developerWorks 的两篇专文分别覆盖控制流与数据流陷阱[^98^]，Kopp 等人从五年间的建模样本中归纳出 15 个高频错误[^99^]。下表结合这些经典清单与 Flowable 生产实践，列出业务系统中最常踩的十类反模式：

| 反模式 | 后果 | 正解 |
|---|---|---|
| 巨型图：一个流程塞下全部业务分支 | 突破 50 元素红线后理解与变更成本陡增，回归不可控[^59^] | 按 7PMG 分解为子流程；图中只留业务骨架 |
| 把回退线画满全图 | 图爆炸且仍覆盖不了任意跳转 | 图上只画业务拒绝分支，回退走 ChangeActivityStateBuilder [^65^] |
| 在流程图中建模重试 | 业务语义被技术细节污染[^61^] | 重试交给异步执行器与 failedJobRetryTimeCycle [^90^] |
| 引擎当 ESB/消息总线 | 被迫中心化部署，牺牲隔离性、抬高中心可用性要求[^100^] | 引擎管流程状态，消息分发交给 MQ；ServiceTask 只调适配层 |
| 监听器里做重操作（远程调用、大批量写） | 监听器在引擎命令事务内同步执行，异常回滚整个流程操作，吞吐被拖垮[^40^] | 监听器只写本地表/发事件，远程动作走 onTransaction=COMMITTED 或 Outbox [^45^] |
| 变量存大对象/业务正文 | 每次变量访问全量抓取，历史写入成倍放大，BLOB 不可查询[^97^] | 变量只存标量与引用 ID（3.9 节） |
| 高频定时轮询外部状态 | 官方论坛实证：分钟级轮询使 ACT_HI_ACTINST 爆炸式增长[^101^] | 改消息/事件驱动，或拉长轮询周期 |
| 待办查询 join 历史表 | 历史表只增不减，查询随时间退化 | 自建待办读模型，由全局监听器同步写入[^31^] |
| 直接 UPDATE act_* 表修数据 | 绕过引擎状态机与缓存，产生不可恢复的脏状态 | 只走 RuntimeService/TaskService；引擎不提供的语义在平台层组合实现（业界共识） |
| 依赖 businessKey 唯一防重 | 唯一约束已被 ACT-1860 移除，重复启动漏网[^27^] | 业务绑定表自建唯一索引，先查后建 |

表中有两类反模式值得单独点出，因为它们的诱惑力最大。**"引擎当 ESB"**源于一个真实错觉：消息方案缺少流程引擎那样的可见性与运维工具，于是有企业干脆让引擎承担工作分发。Rücker 记录过这种做法并划了边界——用作 work distribution 引擎就必须中心化，这与微服务自治的默认方向相悖，属"可以做但须清醒权衡"的用法[^100^]。**监听器重操作**则踩中引擎最容易被忽视的契约：监听器实例共享、在命令事务内同步执行，`isFailOnException` 返回 true 时异常直接回滚引擎事务；通知、报表、外部同步这类动作的正确出口是事务提交后事件或 Outbox，而不是监听器本体。两类反模式的共同病根相同：把"引擎能执行"误读为"引擎该执行"。还有一个不在表中却同样普遍的运维盲区：历史数据默认永久保存，官方 History Cleaning 默认关闭，开启后默认每天凌晨删除结束满 365 天的实例[^30^]——有审计留存要求的系统必须先自建归档再谈清理，不能指望引擎默认值。

### 4.2 场景速查与结语

把三层模式地图按典型场景收敛，给出三组起手组合：

**单体业务系统（单业务线、强一致优先）**：嵌入式引擎 + 同库本地事务打底；门面封装语义化 API；多实例 + 计票 Bean 覆盖会签；驳回走 ChangeActivityStateBuilder 加平台层清理；变量轻量原则从第一天执行。这是成本最低、红利最实在的组合，嵌入式的事务一致性是官方设计而非巧合[^5^]。

**多业务线共用平台（流程中台形态）**：独立流程服务 + 统一待办读模型；业务接入经语义化门面与审批人策略 SPI；跨服务一致性用 Outbox + 幂等消费；版本治理按"部署→切流→暂停"三步走[^28^]。代价是放弃本地事务，换来复用与独立演进。

**微服务编排**：引擎承担编排式 Saga 的角色——超时监控、补偿执行、状态可见性正是纯消息方案缺失的部分[^102^]；补偿用 BPMN 补偿事件建模且必须幂等[^21^]；引擎部署形态按组织规模与一致性需求条件化选择——Camunda 官方主张每服务一引擎并认为公司越大越应去中心化[^14^]，国内流程中台实践则倾向集中以换取统一治理，两者不是对错问题，是组织约束下的不同最优解。

结语只讲一个观点。Flowable 的设计哲学是给机制不给功能：它给你多实例但不给"会签"，给你状态迁移但不给"驳回"，给你定时器但不给"SLA 管理"。这种克制让引擎保持通用，也把语义裁决的责任完整转移给了使用方。于是同一个引擎，在有模式纪律的团队手里是生产力，在没有纪律的团队手里是技术债发生器——巨型图、监听器里的远程调用、塞满业务正文的变量，都是自由度被挥霍的痕迹。模式不是教条，是约束下的取舍记录：它告诉你每个诉求有哪几条验证过的路、各自的代价是什么。引擎的自由度越高，这份纪律越值钱；这也是本文从架构、设计到实现三层反复回到同一主题的用意——**先选模式，再写代码**。

## 参考文献

[1] Flowable. Flowable Open Source（官网产品页）[EB/OL]. https://www.flowable.com/open-source.
[2] GitHub. flowable/flowable-engine Releases（7.0.0 / 7.1.0 / 7.2.0 / 8.0.0 release notes）[EB/OL]. 2023-09-21 至 2026-02-27. https://github.com/flowable/flowable-engine/releases.
[3] onlu.ch. Camunda vs Flowable: A comparison of BPM engines[EB/OL]. 2024-03-11. https://onlu.ch/en/camunda-vs-flowable-a-comparison-of-bpm-engines/.
[4] Flowable BPMN 用户手册中文翻译（tkjohn.github.io）. Flowable BPMN 用户手册（v6.3.0 中文翻译版）5.2 事务[EB/OL]. https://tkjohn.github.io/flowable-userguide/.
[5] Flowable. Flowable Open Source Documentation: Spring integration（Transactions，ch05）[EB/OL]. https://www.flowable.com/open-source/docs/bpmn/ch05-Spring.
[6] Flowable 官方论坛. Transaction management with interacting multiple applications（Tijs Rademakers 答复）[EB/OL]. https://forum.flowable.org/t/transaction-management-with-interacting-multiple-applications/900.
[7] 博客园. 基于Flowable开发业务审批，审批意见应该存流程引擎还是业务数据库？[EB/OL]. 2026-08-18. https://www.cnblogs.com/hibpm/p/22545009.
[8] Bernd Rücker. Moving from embedded to remote workflow engines[EB/OL]. 2022-02-08. https://berndruecker.io/moving-from-embedded-to-remote-workflow-engines/.
[9] 掘金. 工作流-审批中心设计整理[EB/OL]. 2022-04-06. https://juejin.cn/post/7083395183617769503.
[10] CSDN. “十五五”以OA为核心的ERP、PLM、BI、SRM、WMS、MES、MDM、CRM系统集成方案[EB/OL]. 2026-01-26. https://blog.csdn.net/weixin_44094929/article/details/157396729.
[11] 派拉软件. 数字化员工门户（解决方案页）[EB/OL]. 2026-09-23 访问. https://www.paraview.cn/solution/show/40.
[12] microservices.io（Chris Richardson）. Pattern: Transactional Outbox[EB/OL]. 2026-09 访问. https://microservices.io/patterns/data/transactional-outbox.html.
[13] InfoQ. Saga Orchestration Using the Outbox Pattern[EB/OL]. https://www.infoq.com/articles/saga-orchestration-outbox/.
[14] Camunda 官方博客（Bernd Rücker）. The Microservices Workflow Automation Cheat Sheet – to Centralize or Decentralize?[EB/OL]. 2020-03-05. https://camunda.com/blog/2020/03/the-microservices-workflow-automation-cheat-sheet-to-centralize-or-decentralize/.
[15] Camunda. Microservices Orchestration 用例页 FAQ[EB/OL]. 2026-09 访问. https://camunda.com/platform/use-cases/orchestrate-microservices/.
[16] Martin Fowler. What do you mean by “Event-Driven”?[EB/OL]. 2017-02-07. https://martinfowler.com/articles/201701-event-driven.html.
[17] 乌克兰马卡连科大学论文库. Saga 模式与协同/编排权衡（《Microservices Patterns》第 4 章转述）[EB/OL]. 约 2023. https://ekmair.ukma.edu.ua/bitstreams/8f657826-0351-4101-a82a-1a16d548fade/download.
[18] Flowable 官方博客. Orchestration vs. choreography: Why your microservices need a conductor[EB/OL]. 2026-04-22. https://www.flowable.com/blog/business/orchestration-vs-choreography.
[19] Garcia-Molina H, Salem K. Sagas（ACM SIGMOD 1987 论文摘要与 DOI 记录）[EB/OL]. 1987. https://doi.org/10.1145/38713.38742.
[20] microservices.io（Chris Richardson）. Pattern: Saga[EB/OL]. 2026-09 访问. https://microservices.io/patterns/data/saga.html.
[21] Microsoft Azure Architecture Center. Saga 分布式事务模式[EB/OL]. 2025-02-25. https://learn.microsoft.com/en-us/azure/architecture/patterns/saga.
[22] Camunda 官方博客（Bernd Rücker）. Microservices Orchestration Webinar Questions and Answers[EB/OL]. 2020-04-20. https://camunda.com/blog/2020/04/architecture-questions-monitoring-orchestrating-your-microservices-landscape-using-workflow-automation/.
[23] Software Engineering Radio. Episode 351: Bernd Rücker on Orchestrating Microservices with Workflow Management[EB/OL]. 2019-01. https://se-radio.net/2019/01/episode-351-bernd-rucker-on-orchestrating-microservices-with-workflow-management/.
[24] Camunda. Zalando Executes Online Orders（官方案例研究）[EB/OL]. 2017-10-30. https://camunda.com/case-study/zalando/.
[25] Flowable. RuntimeService Javadoc（Flowable Open Source 2025.2 / 8.0.0.6）[EB/OL]. 2025-12-16. https://developer-docs.flowable.com/javadocs/flowable-oss-javadoc/2025.2/org/flowable/engine/RuntimeService.html.
[26] GitCode 博客. 如何通过JeecgBoot集成Flowable实现企业级流程自动化[EB/OL]. 2026-04-02. https://blog.gitcode.com/556564251b26f12e12dbb3a68d9d0d1e.html.
[27] Flowable 官方论坛. Business Key no longer required to be unique（ACT-1860）[EB/OL]. https://forum.flowable.org/t/business-key-no-longer-required-to-be-unique/735.
[28] 阿里云开发者社区. 流程定义升级不影响在途实例：版本隔离、启动冻结与灰度发布实践[EB/OL]. 2026-08-16. https://developer.aliyun.com/article/1756010.
[29] 掘金. 在Flowable开源流程引擎里，待办、已办、在办、我发起、抄送我的，数据分别从哪里查询？[EB/OL]. 2026-08-17. https://juejin.cn/post/7675005880884641838.
[30] Flowable. Flowable Open Source Documentation: History（ch10，含 History Cleaning）[EB/OL]. https://www.flowable.com/open-source/docs/bpmn/ch10-History.
[31] 51CTO 博客. Flowable 待办已办（同一作者 Flowable 系列）[EB/OL]. 2024-03-03. https://blog.51cto.com/u_16114318/9869304.
[32] 51CTO 博客. Flowable 全局监听器[EB/OL]. 2024-03-03. https://blog.51cto.com/u_16114318/9869305.
[33] InfoWorld（Bernd Rücker）. 3 common pitfalls of microservices integration—and how to avoid them[EB/OL]. 2017-08-03. https://www.infoworld.com/article/2263744/3-common-pitfalls-of-microservices-integrationand-how-to-avoid-them.html.
[34] Flowable. Flowable 官方 Javadoc: Package org.flowable.engine.task 包摘要（8.0.0 快照）[EB/OL]. 2026-02-27. https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/task/package-summary.html.
[35] ruoyi-vue-pro 开发指南. 工作流（Flowable）[EB/OL]. 2024-01-26. https://ruoyi.feixin.app/bpm/.
[36] 芋道（ruoyi-vue-pro）官方文档. 项目结构（yudao-module-bpm 模块划分）[EB/OL]. 2022-03-02 起持续更新. https://doc.iocoder.cn/project-intro/.
[37] Flowable. Flowable Open Source Documentation: BPMN 2.0 Constructs（ch07b，含多实例、User Task 自定义分配、事务与并发）[EB/OL]. 2026-09-23 访问. https://www.flowable.com/open-source/docs/bpmn/ch07b-BPMN-Constructs.
[38] 博客园. SpringBoot+Flowable 审批候选人策略设计：十余种 Strategy + Invoker[EB/OL]. 2026-08-02. https://www.cnblogs.com/zhouzhongyan2020/articles/22144684.
[39] Flowable. Flowable 8.0.0 Javadoc: FlowableEventListener[EB/OL]. 2026-02 快照. https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/common/engine/api/delegate/event/FlowableEventListener.html.
[40] Flowable. Flowable Open Source Documentation: Configuration（ch03，Event handlers/History/表结构）[EB/OL]. https://www.flowable.com/open-source/docs/bpmn/ch03-Configuration.
[41] GitCode 博客. RuoYi-Vue-Plus工作流模块：审批流程设计[EB/OL]. 2026-02-04. https://blog.gitcode.com/637289f1bd8a9f0cab965d9168ec6e05.html.
[42] 掘金. Flowable工作流-任务监听器[EB/OL]. 2023-06-29. https://juejin.cn/post/7250044327882948667.
[43] Flowable 官方论坛. EventListener on PROCESS_COMPLETED（onTransaction=committed 示例）[EB/OL]. https://forum.flowable.org/t/eventlisterner-on-process-completed/9423.
[44] Flowable. TaskListener Javadoc（Flowable 8.0.0）[EB/OL]. 2026-02-27. https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/delegate/TaskListener.html.
[45] 博客园. Flowable工作流系统里催办功能设计：站内信、短信、邮件和企业微信[EB/OL]. 2026-09-20. https://www.cnblogs.com/hibpm/p/23043278.
[46] Flowable. Flowable Open Source Documentation: Spring Boot（ch05a）[EB/OL]. 2026-03-16 快照. https://www.flowable.com/open-source/docs/bpmn/ch05a-Spring-Boot.
[47] Workflow Patterns Initiative. Workflow Patterns 官方网站首页[EB/OL]. 1999 起持续维护，2026-09-23 访问. http://www.workflowpatterns.com/.
[48] van der Aalst W M P, ter Hofstede A H M, Kiepuszewski B, Barros A P. Workflow Patterns（Distributed and Parallel Databases, 14(1):5-51）[EB/OL]. 2003-07. https://link.springer.com/article/10.1023/A:1022883727209.
[49] van der Aalst W M P, ter Hofstede A H M. YAWL: Yet Another Workflow Language（Information Systems, 30(4):245-275）[EB/OL]. 2005. https://vdaalst.com/publications/p251.pdf.
[50] Workflow Patterns Initiative. Workflow Control-Flow Patterns: A Revised View（控制流模式页，2006 修订版引言）[EB/OL]. 2006，2026-09-23 访问. http://www.workflowpatterns.com/patterns/control/.
[51] Workflow Patterns Initiative. Evaluations（模式支持度评级语义）[EB/OL]. 2026-09-23 访问. http://www.workflowpatterns.com/evaluations/.
[52] Workflow Patterns Initiative. WCP2 Parallel Split / WCP3 Synchronization 模式页[EB/OL]. 2026-09-23 访问. http://www.workflowpatterns.com/patterns/control/basic/wcp2.php.
[53] Workflow Patterns Initiative. WCP10 Arbitrary Cycles 模式页[EB/OL]. 2026-09-23 访问. http://www.workflowpatterns.com/patterns/control/structural/wcp10.php.
[54] Workflow Patterns Initiative. WCP15 Multiple Instances without a priori Run-Time Knowledge 模式页[EB/OL]. 2026-09-23 访问. http://www.workflowpatterns.com/patterns/control/multiple_instance/wcp15.php.
[55] 掘金. 工作流加签完整设计：前加签、后加签和当前节点加人[EB/OL]. 2026-09-02. https://juejin.cn/post/7680842064037822473.
[56] Workflow Patterns Initiative. WCP16 Deferred Choice 模式页[EB/OL]. 2026-09-23 访问. http://www.workflowpatterns.com/patterns/control/state/wcp16.php.
[57] Workflow Patterns Initiative. WCP25 Cancel Region 模式页[EB/OL]. 2026-09-23 访问. http://www.workflowpatterns.com/patterns/control/new/wcp25.php.
[58] Wohed P, van der Aalst W M P, Dumas M, ter Hofstede A H M, Russell N. On the Suitability of BPMN for Business Process Modelling（BPM 2006, LNCS 4102:161-176）[EB/OL]. 2006-09. http://www.workflowpatterns.com/documentation/documents/BPMN-eval-BPM06.pdf.
[59] Mendling J, Reijers H A, van der Aalst W M P. Seven Process Modeling Guidelines (7PMG)（Information and Software Technology, 52(2):127-136）[EB/OL]. 2010-02. https://research.wu.ac.at/en/publications/seven-process-modeling-guidelines-7pmg-3/.
[60] Camunda. Best Practices — Modeling: Naming BPMN elements（Camunda 8 官方文档）[EB/OL]. 2026-09-23 访问. https://docs.camunda.io/docs/components/best-practices/modeling/naming-bpmn-elements/.
[61] Camunda. Best Practices — Modeling: Creating readable process models（Camunda 8 官方文档）[EB/OL]. 2026-09-23 访问. https://docs.camunda.io/docs/components/best-practices/modeling/creating-readable-process-models/.
[62] CSDN. Flowable28多实例的加签减签[EB/OL]. 2025-07-16. https://blog.csdn.net/CodeTom/article/details/149404203.
[63] Light Docusaurus（lorchr.github.io）. Countersign-and-ParallerApproval（会签与并行审批实现）[EB/OL]. 2024-04-27. https://lorchr.github.io/light-docusaurus/middleware/workflow/Countersign-and-ParallerApproval/.
[64] 简书. flowable多任务实现会签、或签[EB/OL]. 2022-10-21. https://www.jianshu.com/p/5a7313a1e9b0.
[65] Flowable 官方论坛. How to move active state of a process instance to another state?（Tijs Rademakers / Joram Barrez）[EB/OL]. 2018-03-15. https://forum.flowable.org/t/how-to-move-active-state-of-a-process-instance-to-another-state/1754.
[66] Javadoc.io. ChangeActivityStateBuilderImpl（Flowable Engine 6.6.0 API）[EB/OL]. 2020-10-12. https://javadoc.io/static/org.flowable/flowable-engine/6.6.0/org/flowable/engine/impl/runtime/ChangeActivityStateBuilderImpl.html.
[67] Flowable. ChangeActivityStateBuilder Javadoc（Flowable Open Source 7.3.0.16, 2025.1）[EB/OL]. 2025-07-15. https://developer-docs.flowable.com/javadocs/flowable-oss-javadoc/2025.1/org/flowable/engine/runtime/ChangeActivityStateBuilder.html.
[68] CSDN. flowable实战（五）flowable驳回/退回上一步/退回到[EB/OL]. 2019-11-14. https://blog.csdn.net/weixin_40816738/article/details/103077196.
[69] Stack Overflow. Losing history records when using ChangeActivityStateBuilder#moveActivityIdToParentActivityId（含 ExecutionEntityManagerImpl 源码定位）[EB/OL]. 2023-05-05. https://stackoverflow.com/questions/76179401/.
[70] Flowable 官方论坛. ChangeActivityStateBuilder change executionId（joram 回复）[EB/OL]. 2021-02. https://forum.flowable.org/t/changeactivitystatebuilder-change-executionid/7436.
[71] 阿里云开发者社区. SpringBoot整合Flowable【07】- 驳回节点任务[EB/OL]. 2025-01-14. https://developer.aliyun.com/article/1649099.
[72] CSDN. SpringBoot + Flowable实战：如何优雅实现任意节点流程驳回[EB/OL]. 2026-03-14. https://blog.csdn.net/weixin_29196891/article/details/159029975.
[73] 掘金. 工作流里的驳回、退回、撤回、撤销和终止：五种操作不能混为一谈[EB/OL]. 2026-08-17. https://juejin.cn/post/7674826322257412142.
[74] Flowable. TaskService Javadoc（Flowable 8.0.0 all-javadocs）[EB/OL]. 2026-02-27. https://www.flowable.com/open-source/docs/all-javadocs/org/flowable/engine/TaskService.html.
[75] Flowable. Flowable Enterprise Documentation: Human Task（Owner/delegate 语义）[EB/OL]. https://documentation.flowable.com/latest/reactmodel/cmmn/reference/human-task.
[76] 博客园. Activiti流程任务指派、转办以及委派[EB/OL]. 2022-06-21. https://www.cnblogs.com/zhaodefu/p/16397196.html.
[77] blackzs.com. Flowable6–加签和减签的源码解析（AddMultiInstanceExecutionCmd/DeleteMultiInstanceExecutionCmd）[EB/OL]. https://www.blackzs.com/流程引擎/Flowable/Flowable6–加签和减签的源码解析.html.
[78] GitHub. flowable-engine issue #3873: flowable-rest module add/delete multiple instances execution API does not exist[EB/OL]. 2024-04-14. https://github.com/flowable/flowable-engine/issues/3873.
[79] Flowable 官方博客. Handling Asynchronous Operations with Flowable – Part 1: Introducing the new Async Executor[EB/OL]. 2021-04-14. https://www.flowable.com/blog/engineering/handling-asynchronous-operations-with-flowable-part-1-introducing-the-new-async-executor.
[80] Flowable. Flowable Enterprise Documentation: App Deployment[EB/OL]. https://documentation.flowable.com/latest/develop/be/app-deployment.
[81] Flowable. Flowable Open Source Documentation: DMN REST API（ch07）[EB/OL]. https://www.flowable.com/open-source/docs/dmn/ch07-REST.
[82] DeepWiki. flowable/flowable-examples: 7.1 Basic DMN Integration[EB/OL]. https://deepwiki.com/flowable/flowable-examples/7.1-basic-dmn-integration.
[83] Flowable. Flowable Enterprise Documentation: Decision Task 属性[EB/OL]. https://documentation.flowable.com/latest/reactmodel/cmmn/reference/decision-task.
[84] Flowable. Flowable Open Source Documentation: DMN 1.1 Introduction（ch06）[EB/OL]. https://www.flowable.com/open-source/docs/dmn/ch06-DMN-Introduction.
[85] Flowable. Flowable Open Source Documentation: DMN Deployment（ch05）[EB/OL]. https://www.flowable.com/open-source/docs/dmn/ch05-Deployment.
[86] Drools. Drools Documentation: Decision Model and Notation (DMN)[EB/OL]. https://docs.drools.org/latest/drools-docs/drools/DMN/index.html.
[87] Nected Blog. Drools vs Camunda[EB/OL]. 2026-07-24. https://www.nected.ai/blog/drools-vs-camunda.
[88] Flowable. Flowable Open Source Documentation: Advanced — Async Executor（ch18）[EB/OL]. 2020-01-21. https://www.flowable.com/open-source/docs/bpmn/ch18-Advanced.
[89] Flowable 官方博客（José Antonio Álvarez）. Demystifying the Asynchronous Flag (II)（死信作业与重放运维策略）[EB/OL]. 2018-07-16. https://www.flowable.com/blog/engineering/demystifying-the-asynchronous-flag-ii.
[90] Camunda. The Job Executor（Camunda 7.22 用户指南，failedJobRetryTimeCycle 语义）[EB/OL]. 2026-09-23 访问. https://docs.camunda.org/manual/7.22/user-guide/process-engine/the-job-executor/.
[91] Flowable. Flowable Open Source Documentation: Deployment（ch06，流程定义版本机制）[EB/OL]. https://www.flowable.com/open-source/docs/bpmn/ch06-Deployment.
[92] Flowable. Flowable Enterprise Documentation: Model Versioning[EB/OL]. https://documentation.flowable.com/latest/model/versioning-deployment.
[93] Flowable. Flowable Open Source Documentation: Process Instance Migration（ch08）[EB/OL]. https://www.flowable.com/open-source/docs/bpmn/ch08-ProcessInstanceMigration.
[94] GitHub. flowable-engine 源码：SetProcessDefinitionVersionCmd（仅 impl 包与 flowable5 兼容模块）[EB/OL]. 2026-09-23 检索. https://github.com/flowable/flowable-engine/blob/main/modules/flowable-engine/src/main/java/org/flowable/engine/impl/cmd/SetProcessDefinitionVersionCmd.java.
[95] Flowable 官方论坛. Long variable types — why do we need it?（Filip 答复）[EB/OL]. https://forum.flowable.org/t/long-variable-types-why-do-we-need-it/6607.
[96] 51CTO 博客. FLOWABLE 流程引擎分析（ACT_RU_VARIABLE 表结构）[EB/OL]. https://blog.51cto.com/xfxuezhang/5968988.
[97] Flowable. Flowable Open Source Documentation: The Flowable API（ch04，Variables/Exception strategy）[EB/OL]. https://www.flowable.com/open-source/docs/bpmn/ch04-API.
[98] Koehler J, Vanhatalo J. Process Anti-patterns: How to Avoid the Common Mistakes of Business Process Modeling（IBM WebSphere Developer Technical Journal, 10(2)/10(4)）[EB/OL]. 2007-02 / 2007-04. http://www.ibm.com/developerworks/websphere/techjournal/0702_koehler/0702_koehler.html.
[99] Kopp O, Leymann F 等. Analysis of Most Common Process Modelling Mistakes in BPMN Process Models（EuroSPI 2007）[EB/OL]. 2007. https://www.researchgate.net/publication/280527351_Analysis_of_most_common_process_modelling_mistakes_in_BPMN_process_models.
[100] Bernd Rücker. 3 common pitfalls in microservice integration — and how to avoid them（个人博客首发版）[EB/OL]. 2019-07-05. https://blog.bernd-ruecker.com/3-common-pitfalls-in-microservice-integration-and-how-to-avoid-them-3f27a442cd07.
[101] Flowable 官方论坛. Delete history for active instances（分钟级轮询致 act_hi_actinst 膨胀）[EB/OL]. 2021-02-13. https://forum.flowable.org/t/delete-history-for-active-instans/7491.
[102] Camunda 官方博客. The Microservices Workflow Automation Cheat Sheet: The Role of the Workflow Engine[EB/OL]. 2020-02-27. https://camunda.com/blog/2020/02/the-microservices-workflow-automation-cheat-sheet-the-role-of-the-workflow-engine/.
