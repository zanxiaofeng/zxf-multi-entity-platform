# 基于 Flowable 与 BPMN 的 Case 系统和 Onboarding 系统的常用业务模式、设计模式与实现模式

> 定位：Case Management（案例/工单/理赔/信贷审批/投诉处置/案件办理类）与 Onboarding（新客户/员工/商户入驻引导类：KYC → 开户 → 签约 → 激活 → 首用引导）两类系统的**模式目录**——业务怎么建模、架构怎么分层、Flowable 8 + Spring Boot 4 上怎么落地。
> 两类系统的分水岭只有一条：**业务路径是否预先确定**——Onboarding 的推进路径建模期完全确定，系统推着人/客户走；Case 的路径运行期逐步展开，知识工作者拉着系统走。本文以这条分水岭组织：**第二章**是两类系统共享的公共业务模式，**第三、四章**分别是 Case 的动态性模式与 Onboarding 的确定性编排模式，**第五、六章**的架构模式与引擎实现模式对两类系统统一收口。
> 与既有文档的分工：
> - 《[BPMN与Flowable8完全指南](BPMN与Flowable8完全指南.md)》——元素级手册（事件/活动/网关/DMN），本文引用不重复；
> - 《[SpringBoot-Flowable通用公共组件调研](SpringBoot-Flowable通用公共组件调研.md)》——横切组件清单（监听器/死信/Outbox…），本文引用不重复；
> - 本文补齐中间层：**两类系统的业务模式、架构设计模式、引擎实现模式**，并落到本仓库多实体骨架。
> 基线：Flowable 8.0 · Spring Boot 4.1 · JDK 21 · 本仓库《单代码库多实体部署》文档（下称"主文档"）。

---

## 目录

- [一、Case 系统与 Onboarding 系统：一条分水岭](#一case-系统与-onboarding-系统一条分水岭)
  - [1.1 业务本质：确定性编排 vs 探索性处置](#11-业务本质确定性编排-vs-探索性处置)
  - [1.2 行业趋势（2026 视角）](#12-行业趋势2026-视角)
  - [1.3 技术路线决策：结构化、动态模式与 CMMN](#13-技术路线决策结构化动态模式与-cmmn)
- [二、公共业务模式（P1~P10）：两类系统共享的推进骨架](#二公共业务模式p1p10两类系统共享的推进骨架)
- [三、Case 系统模式（P11~P13）：运行期动态性](#三case-系统模式p11p13运行期动态性)
- [四、Onboarding 系统模式（P14~P16）：确定性编排强化](#四onboarding-系统模式p14p16确定性编排强化)
- [五、架构设计模式（D1~D8）：两类系统共用一套架构](#五架构设计模式d1d8两类系统共用一套架构)
- [六、实现模式（Flowable 8 落地层 I1~I10，含代码）](#六实现模式flowable-8-落地层-i1i10含代码)
- [七、反模式清单（AM1~AM11）](#七反模式清单am1am11)
- [八、与本仓库骨架的映射与落地路线](#八与本仓库骨架的映射与落地路线)

---

## 一、Case 系统与 Onboarding 系统：一条分水岭

### 1.1 业务本质：确定性编排 vs 探索性处置

Onboarding 系统处理的是**路径可预先完全枚举**的引导类工作：新客户/员工/商户入驻（KYC 审核 → 开户 → 签约 → 激活 → 首用引导），所有步骤与分支在建模期已知，运行期只按数据选择走哪条分支——本仓库 `order-approval` 与此同构。Case 系统（案例管理）处理的是**知识工作者主导、路径不可预先完全枚举**的工作：一地理赔案要视现场情况决定是否引入调查/鉴定/法务，一宗投诉要视沟通进展决定升级还是和解。两类问题常被并称为"流程类系统"，但解法重心完全不同：

| 维度 | Onboarding 系统（结构化流程，路径确定） | Case 系统（案例，路径运行期展开） |
| --- | --- | --- |
| 目标 | 把一条预先确定的路径跑完（完成入驻/激活） | 达成某个业务结果（结案条件） |
| 执行路径 | 建模期完全确定；分支建模期枚举，运行期按数据选择 | 运行期按信息逐步展开，允许回溯、插入、并行探索 |
| 驱动者 | 流程定义（系统推着人/客户走） | 知识工作者（人拉着系统走）+ 规则约束 |
| 建模标准 | BPMN 2.0（结构化流程即够，无需 CMMN） | BPMN 2.0 动态构造，或 CMMN 1.1（Stage/Milestone/Sentry/Discretionary Task） |
| 典型度量 | 转化漏斗、阶段流失率、端到端入驻时长、SLA 违约率 | 里程碑达成率、SLA 违约率、结案质量 |
| 结束语义 | End Event 被走到（激活完成），或显式终止（放弃/拒绝） | 结案条件满足（可能提前结案、可能多分支同时挂起后一并收敛） |
| 本仓库例子 | `order-approval`（审批拓扑固定）——Onboarding 类系统的直接样板 | 理赔案、工单、尽调案（尚未建） |

关键洞察：**Case ≠ 更复杂的流程图，Onboarding ≠ 更简单的 Case**。把 Case 硬画成一张巨型 BPMN（把所有可能路径都画出来）会得到"意大利面流程"——分支数随业务知识指数增长，改一处动全身（AM1）；Case 的正确抽象是把"可做的动作集合 + 推进条件 + 收敛条件"建模成**计划模型**，把路径决策权交给运行期的知识工作者（第三章）。反过来，Onboarding 的路径既然确定，就**直接画确定性的图**——门禁用网关、并行任务用多实例、失败用错误边界事件（第四章），不引入任何运行期动态构造（AM11）。而无论路径确定与否，两类系统真正做的事是同一件：**一个领域聚合，被流程引擎按编排规则推进，全程留痕**——这就是第二章公共模式与第五章架构模式的由来。

### 1.2 行业趋势（2026 视角）

本节主要回答 Case 类系统的选型争议（用不用 CMMN、动态能力放哪）；Onboarding 路径确定，标准 BPMN 结构化流程即是终态，无选型问题——仅趋势 4/5/6（事件驱动审计、收件箱、AI 分派）与两类系统都相关。

1. **CMMN 规范已冻结**：OMG CMMN 停留在 1.1（2016），之后无新版本演进——规范层不再有新特性可期。来源：[OMG CMMN 1.1](https://www.omg.org/spec/CMMN/1.1/)。
2. **主流厂商路线分化，CMMN 选型收敛到 Flowable**：Camunda 8（Zeebe 架构）**不提供 CMMN 引擎**，官方立场是以 BPMN + 人工协作覆盖 case 类场景；Camunda 7（含 CMMN 支持）社区版已于 2025 年 10 月 EOL。要在新项目里用原生 CMMN，Flowable 已是事实上的少数主流开源选择（Flowable 8 持续维护 BPMN/CMMN/DMN 三引擎）。
3. **"BPMN 为核 + 动态能力"成为务实主流**：多数团队不引 CMMN，而是用 BPMN 的动态构造（事件子流程、多实例、包容网关、消息关联、运行期干预 API）覆盖 80% 的 case 需求——动态性"建模进流程"而不是"交给第二引擎"。本文 1.3 给出选型判据。
4. **事件驱动与全程审计成为标配**：流程状态变更 → 领域事件 →（Outbox 可靠外发 / 审计投影）已是行业共识（见《通用公共组件调研》组件 1/2/12；主文档 8.1 规则 11）。
5. **任务收件箱/工作台产品化**：开源版 Flowable 不含成品收件箱（Flowable Work 为商业产品），自建收件箱（TaskService 查询 + 认领接口）是自研 Case/Onboarding 系统的固定工作量。
6. **AI 辅助分派（case triage）兴起**：用 LLM/分类模型做工单初分类、优先级建议、下一步动作推荐，人工确认后执行——落地形态是"策略接口的一个新实现"（本文 D5），不改变架构。

### 1.3 技术路线决策：结构化、动态模式与 CMMN

技术路线按"路径确定与否 + 动态程度"分三档，**先选档，再谈引擎**：

| 档位 | 适用 | 形态 |
| --- | --- | --- |
| 第一档：BPMN 结构化流程 | **Onboarding**（路径建模期完全确定） | 标准顺序流 + 网关分支 + 多实例，零动态构造（P14~P16） |
| 第二档：BPMN 动态模式 | **Case 的常态**——不确定性是"有限集合内的排列组合"（知道有哪些动作，不知道走哪些、按什么顺序） | 事件子流程、多实例、包容网关、消息关联、运行期干预 API（P11~P13、I2/I4/I6/I8） |
| 第三档：CMMN 引擎 | **Case 的真自由场景**——动作集合本身开放、由工作者即兴决定 | 独立 CMMN 引擎（`ACT_CMMN_*`，I7） |

Onboarding 无需往下选档：第一档就是终态，引入任何动态构造都是过度设计（AM11）。Case 在第二、三档之间的判据（Flowable 8 内置独立 CMMN 引擎 `flowable-cmmn-engine`，独立表 `ACT_CMMN_*`，可与 BPMN 引擎共库部署）：

| 判断问题 | 是 → CMMN 引擎 | 否 → BPMN 动态模式 |
| --- | --- | --- |
| 任务顺序是否完全由知识工作者运行期决定（没有主路径）？ | ✅ | 主路径明确，仅局部动态 |
| 是否需要"自主任务"（工作者按需插入预注册动作，无触发条件）？ | ✅ | 事件子流程 + 消息触发可覆盖 |
| 是否需要 Stage 级别的进入/退出条件编排（哨兵逻辑复杂）？ | ✅ | 条件网关 + 边界事件可覆盖 |
| 是否需要与 BPMN 流程互相调用（ProcessTask/CaseTask）？ | ✅（原生支持） | Call Activity 单向即可 |
| 团队是否已具备 CMMN 建模能力？ | ✅ | 学习成本（建模工具、人员、排障经验都稀缺） |
| 运维是否愿意多养一套引擎 schema（`ACT_CMMN_*` 迁移/升级核对）？ | ✅ | 一套 `ACT_*` 即可 |

**经验法则**：BPMN 动态模式通常够用且运维成本低一半；只有真自由场景才值得上 CMMN。两者也可共存：CMMN 管案例全局推进（Stage/Milestone），其中确定性的片段下沉为 BPMN 子流程（ProcessTask）——这是 CMMN 规范的设想形态，但双引擎的建模/运维成本要预先算账（本仓库 7.3 的 ACT_* 治理纪律要复制一份给 `ACT_CMMN_*`）。

---

## 二、公共业务模式（P1~P10）：两类系统共享的推进骨架

不管路径确定与否，两类系统都要回答同样的十个问题：业务事实放哪（P1）、推进感从哪来（P2）、人工任务怎么管（P3）、超时怎么办（P4）、类型差异怎么收敛（P5）、大小事怎么拆合（P6）、留痕怎么做（P7）、重复与并发怎么防（P8）、待办怎么看（P9）、结束怎么收（P10）。每条模式：**意图 → 做法 → Flowable 落点 → 纪律**，Case 与 Onboarding 双侧对照。

### P1 申请单/案卷聚合（Application / Case File Aggregate）

- **意图**：全部业务事实（材料、沟通记录、结论）收敛为一个领域聚合根，流程只是它的"推进器"。
- **做法**：Case 侧 `ClaimCase`（理赔案）、Onboarding 侧 `OnboardingApplication`（入驻申请单）作聚合根，持久化在自己的业务表；流程实例与聚合 1:1，`businessKey = caseId`（入驻场景 `businessKey = applicationId`，语义相同）。流程变量**只放轻量标识**（caseId 等值对象字符串），业务数据按需经领域对象重载。
- **Flowable 落点**：`startProcessInstanceByKey(key, businessKey, vars)` 的 businessKey；CMMN 侧 `createCaseInstanceBuilder().businessKey(...)`。
- **纪律**：领域值对象不进流程变量（避免序列化膨胀与历史表膨胀）；delegate 内按 caseId 重新加载领域对象——本仓库 `OrderApprovalService` 已是该模式的审批流程样板（文档 7.1），也是 Onboarding 类系统的直接参照。

### P2 阶段—里程碑驱动（Stage / Milestone）

- **意图**：Case 的推进感、Onboarding 的进度条，都来自"到了哪个阶段、达成了哪些里程碑"，而不是"流程图走到哪个框"。
- **做法**：BPMN 路线（两类通用）——嵌入式子流程表达阶段（Stage），子流程内以**消息/条件事件 + 空结束事件**表达里程碑（到达即记事件、流程继续等下一个触发）；CMMN 路线（仅 Case，第三档）——`stage` 与 `milestone` 是一等 plan item，引擎落 `ACT_CMMN_RU_MIL_INST`（历史 `ACT_CMMN_HI_MIL_INST`）。
- **Flowable 落点**：CMMN `createMilestoneInstanceQuery()`；BPMN 侧以自定义消息/信号事件打里程碑指标。
- **纪律**：里程碑是**业务事实**不是节点名——对外语义用"材料齐备""责任认定完成""KYC 通过""账户激活"，不暴露引擎活动 ID。

### P3 分派—认领—处置生命周期（Triage → Assign → Claim → Work → Complete）

- **意图**：人工任务的标准化生命周期：受理分派（triage）→ 指派/候选（assign）→ 认领（claim）→ 处置（work）→ 提交完成（complete）——Case 的调查处置与 Onboarding 的 KYC 人工复核、合规人工审核同构。
- **做法**：User Task 的 `assignee` / `candidateUsers` / `candidateGroups` 三级表达"直派 / 候选人 / 候选组"；分派规则外置为策略（P5 类型差异化 + D5 策略化），认领后的所有权转移由引擎保证（重复认领抛 `FlowableTaskAlreadyClaimedException`）。
- **Flowable 落点**：`TaskService.createTaskQuery().taskCandidateGroupIn(...)`、`claim()`、`complete(id, vars)`（I1）。
- **纪律**：分派策略变化（按负载/技能/实体差异）是扩展点不是 if-else——本仓库 `TaskAssignmentListener` + `TaskAssignmentRule` 已落地（组件 6），实体差异用 `@ForEntity` 限定。

### P4 SLA 与升级督办（Escalation Ladder）

- **意图**：超时不是"驳回重来"而是"逐级升级"：Case 侧超 24h 提醒经办人 → 超 48h 通知主管 → 超 72h 升级至部门负责人；Onboarding 侧同构——限时完成 KYC、客户限时补件、限时激活，超时催办/流失预警。
- **做法**：User Task 挂**非中断定时器边界事件**（`cancelActivity="false"`）做提醒路径，中断型做超时强制流转；升级阶梯用多级边界事件或定时器中间事件串联；CMMN 可在 stage 级挂 timer sentry。
- **Flowable 落点**：`timerEventDefinition`（I3）；升级记录以领域事件出栈供工作台展示。
- **纪律**：SLA 时限是**配置不是图**——同一定义在不同实体/租户下时限不同，用流程变量或配置注入 `PT24H` 表达式，不要为每个时限改一张图（本仓库多实体差异纪律的直系应用）。

### P5 模板与类型化（Template / Typology）

- **意图**：同类案例/入驻申请共享推进骨架，差异局部化：工单按产品线、理赔按险种、投诉按渠道、入驻按渠道与客户类型（个人/商户）。
- **做法**：每类型一套流程/Case 定义（key 类型化，如 `claim-case-motor` / `onboarding-merchant`），公共片段用 Call Activity 复用；版本演进走"新版本定义 + 在途迁移"（I8）。本仓库多实体骨架下，**实体差异即 Case 类型/Onboarding 类型差异**：BPMN 放实体模块 `processes/`，随 profile 裁剪（文档 7.2）。
- **纪律**：模板数量爆炸是 smells——公共骨架抽 Call Activity，模板间只保留真实差异节点；同 key 双定义由装配冒烟守护（本仓库 7.4）。

### P6 父子与关联实例（Instance Graph）

- **意图**：大案拆小案（理赔案 → 子调查案、子法务案），子案结论回传父案；或同主体多案关联（同一客户的关联投诉合并处理）。Onboarding 侧同构：大商户主入驻 → 各门店/子账户开通子流程。
- **做法**：父案 Call Activity / CMMN `ProcessTask` 启动子案，businessKey 用子案 ID、流程变量带 `parentCaseId`；回传用消息关联（子案结束事件发消息，父案 Receive Task / 事件子流程接收）。
- **纪律**：父子是**引用关系不是嵌套数据**——子案变量不复制父案大对象，只传 ID；跨案查询走业务投影（D7），不走引擎跨流程 join。

### P7 全程审计轨迹（Audit Trail）

- **意图**：Case 的法律与合规属性、Onboarding 的 KYC/合规留痕都要求"谁在何时看了/改了/决定了什么"全程可溯——这是两类系统区别于普通工作流的硬需求。
- **做法**：三层事件源——(1) 引擎事件（`FlowableEngineEventListener` 全量桥接，组件 1/2）；(2) 业务领域事件（`OrderCreatedEvent` 式，AFTER_COMMIT 落审计，主文档 8.1 规则 11）；(3) 任务级操作（认领/批注）经 TaskService 事件或应用层显式记录。历史查询用 `HistoryService`（`ACT_HI_*`）+ 业务审计表双轨。
- **纪律**：审计失败不得回滚业务事务（监听器 `isFailOnException()=false`）；审计条目必须带实体维度（MDC/EntityContext，Job 线程经 delegate 基类重建）。

### P8 幂等与并发控制

- **意图**：入口多（API/MQ/批量导入）、周期长，重复触发与并发修改是常态（用户双击重复提交入驻申请、MQ 重投、批量重放）。
- **做法**：(1) 启动幂等——businessKey 唯一约束（业务表唯一键 + 引擎侧启动前查询）；(2) 消息幂等——关联键唯一 + 消费端去重表（Outbox 语义，组件 12）；(3) 认领并发——`claim()` 原子校验 assignee；(4) 业务并发——案卷/申请单聚合乐观锁（`@Version`），引擎变量只放 ID 避免脏读旧快照。
- **纪律**：不要依赖"前端防重"——重复提交一定会发生。

### P9 工作台收件箱（Task Inbox）

- **意图**：工作者的统一待办视图：我的待办 / 组待办 / 我经办的 / 即将超时，支持批量认领与跳转处置。
- **做法**：TaskService 查询封装查询端口（`taskCandidateOrAssigned(userId)` 一步覆盖候选+已认领）；超时预警用"SLA 到期时间"冗余进任务变量 + 定时扫描，或直接用 `dueDate` + 查询排序；列表页数据走投影表（D7）避免高频压引擎。
- **纪律**：收件箱 API 是**读侧契约**——按用例定义窄接口（ISP），不要把 `TaskService` 原样暴露给 Controller（引擎 API 不出基础设施层，D1）。

### P10 结束归档与统计投影（Closure & Projection）

- **意图**：结束即"结束条件以业务语义确认"，之后归档（历史表迁移/业务表状态机收尾）并沉淀统计。结束语义两型，对应 1.1 的分野——**Case 结案条件驱动**：条件满足即可结案，可能提前结案、可能多分支同时挂起后一并收敛（P13）；**Onboarding 路径完成驱动**：End Event 走到（激活完成）或显式终止（放弃/拒绝），不存在"跳步结案"（跳过 KYC 直接激活是合规事故）。
- **做法**：结束动作完成流程 End Event → `PROCESS_COMPLETED` 事件 → AFTER_COMMIT 落结束投影（时长、里程碑明细、经办链）；报表只读投影表，不打引擎。历史数据用 Flowable 的 history level（`none/activity/audit/full`）控制粒度，配合定期归档作业。
- **纪律**：结束条件判断在**领域层**（案卷/申请单聚合方法），引擎只负责"确认后的收尾编排"——反转 P1 的原则：业务状态是真相，流程状态是投影。

---

## 三、Case 系统模式（P11~P13）：运行期动态性

Case 区别于 Onboarding 的全部差异都在"路径运行期展开"这一件事上，它分解为三个问题：**哪些动作可做**（P11 动态插入）、**什么时候可做**（P12 哨兵）、**何时收敛**（P13 并行探索与提前结案）——正是 1.1 关键洞察里"计划模型"的三要素。

### P11 动态任务插入（Ad-hoc / Discretionary）

- **意图**：运行期向进行中的案例插入预注册的动作（"补一份鉴定"），不打断主推进。
- **做法**：三条路线，按动态性递增——
  1. **事件子流程 + 消息触发**（BPMN）：把可插入动作建模为挂起的事件子流程，工作者触发 = 发关联消息（I6）。动态集合是"预注册"的，安全可控；
  2. **多实例 + 集合驱动**（BPMN）：待办动作清单放集合变量，多实例按集合展开，新增动作 = 集合加元素（需要配合运行期变量修改）；
  3. **CMMN Discretionary Task / 手工启动 plan item**：预注册动作以 `enabled` 状态挂起，工作者 `startPlanItemInstance()` 按需启动（I7）——这是规范语义，动态性最强。
- **纪律**："任意注入任意节点"没有一等公民 API（Flowable 的运行期干预是 move 语义，见 I8），**不要**用反射改流程定义——动态性必须事先建模进定义。
- **Onboarding 对位**：路径确定、动作集合建模期固定，全部动作直接画进主图，不存在本模式——给路径确定的系统引入动态插入即 AM11。

### P12 哨兵与进入条件（Sentry / Entry-Exit Criteria）

- **意图**：动作不是"上游做完就做"，而是"条件齐了才可做"（如：责任认定 + 材料齐备 → 才可进入核赔）。
- **做法**：BPMN——并行汇聚网关 + 条件流表达"多事实汇聚后放行"；CMMN——`sentry`（`onPart` 事件部分 + `ifPart` 条件部分）挂在 plan item 的 `entryCriterion` 上，多条件齐备才激活。
- **纪律**：条件表达式引用的变量是"事实"不是"控制标志"——让材料完成度、鉴定结论这些业务事实驱动哨兵，避免引入一堆 `xxxDone` 布尔流程变量（控制态泄漏进数据）。
- **Onboarding 对位**：前置条件建模期已知，用顺序流 + 网关静态表达即可（P14 阶段门禁是其静态形态），无需运行期哨兵机制。

### P13 并行探索与提前结案（Parallel Exploration & Early Closure）

- **意图**：Case 常需要并行开多条处置路径（同时走调解与法务准备），信息明朗后择一收敛、其余终止；或结案条件提前满足时直接结案，不必跑完剩余动作。1.1 表中"允许回溯、插入、并行探索""可能提前结案、多分支同时挂起"说的就是本模式。
- **做法**：BPMN——包容网关（inclusive）同时激活多条探索分支，事实明朗后经事件/条件收敛：选中分支继续、其余走终止结束事件；提前结案用消息/信号触发结束路径（事件子流程响应"结案信号"，终止其余分支）。CMMN——多 plan item 并行激活天然支持，`exitCriterion` 挂 stage 或 case 全局。
- **Flowable 落点**：`inclusiveGateway`、`terminateEndEvent`；CMMN `exitCriterion`。
- **纪律**：探索分支也是**预注册**的（与 P11 同源——枚举有限条探索路径，不是运行期发明路径）；提前结案的判定在**领域层**（结案条件是聚合方法，P10 纪律），引擎只执行"确认后终止剩余分支"。
- **Onboarding 对位**：路径确定则不存在"探索后收敛"，也不允许中途跳跃结案——Onboarding 的提前结束只有显式终止（放弃/拒绝）一条路（P10）。

---

## 四、Onboarding 系统模式（P14~P16）：确定性编排强化

路径确定不等于没有模式——确定性流程的重心从"路径决策"移到**阶段闸门的严谨性**上：进下一阶段前条件是否齐（P14 门禁）、阶段内任务是否全就绪（P15 清单）、自动步骤失败是否有人兜底（P16 失败重试）。三条模式共同的反面是 AM11：不要用 Case 的动态构造解决 Onboarding 的问题。

### P14 阶段门禁（Stage Gate）

- **意图**：阶段推进必须先过集中校验：KYC 通过、协议签署、材料齐备——全部满足才放行下一阶段；不满足分流到补件 / 人工复核 / 终止，出口都是建模期枚举的显式分支。
- **做法**：每阶段出口设一个门禁节点——并行汇聚收齐本阶段事实变量 → 独占网关按事实条件分流（放行/补件/终止）；门禁规则复杂（多维评分、按客户类型差异化）时下沉 DMN（I5）。
- **Flowable 落点**：`exclusiveGateway` + 条件流；或 DMN 决策表（如 `onboarding-gate-{entity}`，随实体模块部署）。
- **纪律**：门禁条件引用**业务事实**变量（与 P12 纪律同源——事实驱动，不造 `xxxDone` 控制标志）；"门禁不通过"是**正常分支不是异常**——补件、终止走显式顺序流，不走引擎异常路径；门禁规则按实体/客户类型差异外置（DMN 表或策略，D5），不复制流程图。

### P15 并行就绪清单（Parallel Checklist）

- **意图**：一个阶段内的并行任务集合（开通账号、合规签署、培训、设备发放）**全部完成**才汇聚进入下一阶段——"清单式入职"，清单本身是配置。
- **做法**：多实例任务按集合变量展开（I4），并行汇聚点天然构成就绪屏障；允许"够数即过"的场景（如 5 项签署过 4 项即可）用 `completionCondition`。
- **Flowable 落点**：`multiInstanceLoopCharacteristics` + `flowable:collection`（I4）。
- **纪律**：**清单是配置不是图**——新增/裁剪入职任务 = 集合加删元素，不改 BPMN；集合由领域服务按 Onboarding 类型（P5：个人/商户、实体差异）生成，不写死在流程变量初始值；单项任务的失败处理仍走 P16。

### P16 失败—重试—人工介入（Failure → Retry → Human Escalation）

- **意图**：自动步骤（调外部系统：开户、发卡、发通知）的失败不留暗账：错误边界事件捕获 → 引擎级重试 → 仍败转人工任务 → 人工处置后恢复推进或终止。
- **做法**：外部调用的 Service Task 挂错误边界事件（或错误事件子流程），失败分支进入带重试语义的路径（本仓库 `failedJobRetryTimeCycle` 引擎级重试已落地，组件 4 同源）；重试耗尽转 User Task（`candidateGroups=ops`），人工完成后经网关回主路径或走终止出口。
- **Flowable 落点**：`errorEventDefinition` 边界事件 + `failedJobRetryTimeCycle`。
- **纪律**：每个自动步骤的失败出口**显式建模**——外部系统超时/失败不能让实例停在原地等 Job 死信；人工介入是正常分支（同 P14 纪律），恢复用建模期画好的回主流顺序流，**不用**运行期 move 改道（I8 是异常纠偏手段，不进设计）。

---

## 五、架构设计模式（D1~D8）：两类系统共用一套架构

路径确定与否改变的是第二章~第四章的业务模式，**不改变架构**：引擎隔离、编排器退化、领域状态机、事件纪律、策略外置、Saga、投影、装配冒烟，两类系统一套到底。这也是本文把架构模式独立成章、不按系统拆分的原因。

### D1 六边形隔离：引擎 API 不出基础设施层

引擎 API（`RuntimeService`/`TaskService`/…）只出现在 `infrastructure.engine` 适配器内；领域与应用层只见自研端口（`OrderApprovalPort`、`NotificationPort`）。收益：换引擎（Flowable → Camunda 8 是真实存在的迁移压力，见 1.2 趋势 2）只改适配器；ArchUnit 守护（本仓库 onion 分层已强制）。**CMMN 若引入，同样只经适配层暴露 `CasePort` 式窄端口。**

### D2 流程编排器退化：Process Manager / 契约 key 发起

业务服务不知道"流程长什么样"，只知道"有个叫 `order-approval` 的推进契约"——代码从流程编排者退化为任务实现者（本仓库 `OrderApprovalService` 的 Javadoc 表述）。跨服务协作采用 Process Manager 模式：编排逻辑由流程定义承担，业务代码只实现各步任务。来源：[microservices.io — Process Manager](https://microservices.io/patterns/data/process-manager.html)。

### D3 领域状态机与流程编排的边界：领域管状态、流程管编排

业务状态机属于**领域模型**（聚合的领域方法 + 类型化异常拒绝非法迁移）——Case 侧如 `OPEN → IN_REVIEW → RESOLVED/CLOSED`，Onboarding 侧如 `DRAFT → KYC_PENDING → ACTIVE/TERMINATED`；流程引擎负责"什么时候调哪个领域方法"的**编排与时序**。判断：一个状态迁移不经过引擎也能合法发生吗（如离线补录）？能 → 它属于领域状态机，引擎只是触发器之一。这条边界防止两种癌变：状态机画进 BPMN（图即代码，改状态机 = 改图）或引擎变量当业务状态（查询全压引擎）。

### D4 领域事件 + AFTER_COMMIT 的副作用纪律

事务内的副作用（审计、通知、投影更新）一律走领域事件 + `@TransactionalEventListener(AFTER_COMMIT)`；跨进程可靠投递走 Transactional Outbox（组件 12）。引擎事件桥接出来的 `FlowableProcessEvent` 订阅者同样遵守——**带副作用必 AFTER_COMMIT，只读观测可同步**。来源：本仓库主文档 8.1 规则 11、《通用公共组件调研》组件 2。

### D5 策略 + 注册表：规则实体化为扩展点

分派规则（P3）、风控门槛、SLA 时限、triage 分类、Onboarding 门禁规则（P14，含 AI 实现）都做成策略接口 + 运行时注册表（本仓库 `TaskAssignmentRule` + `PolicyRegistry`、`@ForEntity` 契约测试基类守护）。策略实现禁止裸 `@Profile`，统一 `@ForEntity`（主文档 5.10.1）。AI 分派的落地形态即"新增一个 `TriageStrategy` 实现"，架构零改动。

### D6 Saga / 补偿：跨系统长事务

Case 处置与 Onboarding 的外部调用（开户、发卡、登记）都常横跨多个外部系统。编排式 Saga：每个本地事务 + 补偿动作建模为流程片段，失败路径走补偿边界事件/补偿事件；引擎的持久化状态天然就是 Saga 的执行记录。来源：[microservices.io — Saga](https://microservices.io/patterns/data/saga.html)。与 D4 配合：补偿触发经事件解耦，不在事务内直接调外部。

### D7 CQRS 轻量投影：查询侧绕开引擎

Case 列表、Onboarding 进度看板、统计报表、跨案检索走**事件驱动的投影表**（`PROCESS_COMPLETED`/里程碑事件 → 更新 projection 表），读写分离不引入完整 CQRS 框架。理由：引擎查询 API（`TaskQuery`/`HistoricProcessInstanceQuery`）适合点查与收件箱，不适合多维度报表（变量过滤走 `ACT_*` 大表代价高）。

### D8 模板方法 + 装配冒烟：流程差异外置的自守护

两实体同 key 不同拓扑的 BPMN（文档 7.2）+ 装配冒烟测试逐字比对（同 key 唯一、delegate 全装配、拓扑断言，7.4）+ delegate 模板基类（`EntityContextAwareDelegate`）。这是"差异外置到实体模块"能安全演进的前提：**没有装配冒烟，BPMN 与代码的契约漂移只能等运行期才炸**——引擎不做启动期 delegate 校验。`order-approval`（拓扑固定）正是 Onboarding 类系统该模式的直接样板。

---

## 六、实现模式（Flowable 8 落地层 I1~I10，含代码）

I1~I6、I8~I10 两类系统通用；I7（CMMN）仅 Case 第三档。I 系列代码均出自本仓库骨架验证过的 API 用法。

### I1 人工任务：候选、认领、完成【通用】

```xml
<userTask id="riskApprove" name="风控审批"
          flowable:candidateGroups="risk-officer" flowable:formKey="risk-approve"/>
```

```java
// 收件箱查询（候选 + 已认领一步覆盖）
List<Task> inbox = taskService.createTaskQuery()
        .taskCandidateOrAssigned(userId)
        .orderByTaskCreateTime().desc()
        .list();
// 认领（并发安全：已认领任务被他人 claim 抛 FlowableTaskAlreadyClaimedException）
taskService.claim(taskId, userId);
// 完成并输出事实变量（P12/P14 纪律：输出业务事实，不是控制标志）
taskService.complete(taskId, Map.of("approved", true, "riskNote", "…"));
```

### I2 消息关联：等待—唤醒【通用】

```java
// 外部事件（Case：补充材料到齐；Onboarding：客户补传材料、外部开户系统回调）
// 定向唤醒某流程实例：businessKey 定位 + 消息名定位
var execution = runtimeService.createExecutionQuery()
        .processInstanceBusinessKey(caseId)
        .messageEventSubscriptionName("supplementMaterialReceived")
        .singleResult();
if (execution == null) { /* 实例不在等待态：按幂等策略落事件表（P8） */ }
else { runtimeService.messageEventReceived("supplementMaterialReceived", execution.getId(), vars); }
```

启动侧对应消息启动事件 `startProcessInstanceByMessage(...)`；跨进程投递经 Outbox 保证不丢（组件 12）。消息名是**流程契约的一部分**，与 BPMN `messageRef` 同源管理。这是 Onboarding「等待—唤醒」的落点：等待点建模期画好（Receive/消息事件），外部事件到达定向唤醒——不需要 I6 的动态插入。

### I3 定时器边界事件：SLA 提醒与超时流转【通用】

```xml
<!-- 非中断：到点提醒，主任务继续（P4 阶梯第一级） -->
<boundaryEvent id="remind24h" attachedToRef="investigate" cancelActivity="false">
  <timerEventDefinition><timeDuration>${slaRemindDuration}</timeDuration></timerEventDefinition>
</boundaryEvent>
<!-- 中断：强制流转到上级处理（P4 阶梯最后一级） -->
<boundaryEvent id="breach72h" attachedToRef="investigate" cancelActivity="true">
  <timerEventDefinition><timeDuration>${slaBreachDuration}</timeDuration></timerEventDefinition>
</boundaryEvent>
```

`timeDuration` 引用变量 → SLA 时限配置化（P4 纪律）；Job 形态执行，重试/死信语义与组件 4 相同。

### I4 多实例：会签 / 或签 / 并行清单【通用】

```xml
<userTask id="committeeSignoff" name="委员会会签" flowable:candidateGroups="committee">
  <multiInstanceLoopCharacteristics isSequential="false"
          flowable:collection="committeeMembers" flowable:elementVariable="member">
    <completionCondition>${nrOfCompletedInstances / nrOfInstances >= 0.5}</completionCondition>
  </multiInstanceLoopCharacteristics>
</userTask>
```

并行 + `completionCondition` 即或签语义；`isSequential="true"` 即串行传阅。会签人数来自变量集合 → 委员名单配置化，不改图。Onboarding 的并行任务清单（P15）同用多实例——「清单即集合变量」，与 Case 会签共用一套机制。

### I5 DMN 外置决策：triage 分类与规则表【通用】

```java
// 规则变更不发版：决策表（案例类型 × 优先级 × 路由组）独立部署
// Flowable 8 只有 builder API（无 executeDecisionByKey 便捷方法）
Map<String, Object> result = dmnDecisionService.createExecuteDecisionBuilder()
        .decisionKey("case-triage")
        .variables(Map.of(
                "caseType", claimCase.type().name(), "amount", claimCase.amount().value()))
        .executeWithSingleResult();
```

适用：命中策略类的结构化决策（分派到哪个组、是否自动结案、Onboarding 门禁放行判定 P14）用 DMN；涉及推理链条的判断留在领域服务。Flowable DMN 的支持边界见《BPMN与Flowable8完全指南》第四部分。

### I6 事件子流程：动态插入与异常响应【通用，双用途】

```xml
<!-- 事件子流程：triggeredByEvent="true" 必备（缺了是普通嵌入子流程，部署校验即失败）；
     非中断需在开始事件显式 isInterrupting="false"（默认中断型，与"不打断主推进"语义相反） -->
<subProcess id="supplementFlow" triggeredByEvent="true">
  <startEvent id="suppStart" isInterrupting="false">
    <messageEventDefinition messageRef="supplementMaterialReceived"/>
  </startEvent>
  <userTask id="reviewSupplement" name="复核补充材料" flowable:candidateGroups="claim-officer"/>
  <endEvent id="suppEnd"/>
</subProcess>
```

**Case 用法（P11 路线 1）——动态插入动作的主力模式**：非中断事件子流程，主推进不受影响，插入动作完成后汇入正常路径；动态集合 = 预注册的事件子流程集合，比"运行期改图"安全一个量级。

**Onboarding 用法——全局事件的异常响应，不是动态插入**：确定性流程里事件子流程的合法位置是响应预定义的全局事件，典型是取消/撤回——收到取消消息的**中断型**事件子流程（`isInterrupting` 默认 true）终止当前阶段所有活动、走清理与终止路径（P10 的显式终止语义）。它不改变主路径拓扑（主路径仍是建模期画好的），与 P11 的"插入新动作"是两回事。

### I7 CMMN 引擎落地（真自由场景，1.3 第三档）【Case】

依赖与配置（本仓库规矩：starter 进 core，Enforcer 锚定版本，`ACT_CMMN_*` 归 Flyway 管、`database-schema-update=false`，并复制 7.3 的升级核对纪律——注意 CMMN 引擎同样有 IDM 依赖）：

```xml
<dependency>
    <groupId>org.flowable</groupId>
    <artifactId>flowable-spring-boot-starter-cmmn</artifactId>
</dependency>
```

```java
// 发起案例（businessKey = caseId，变量只放轻量标识——P1）
String caseInstanceId = cmmnRuntimeService.createCaseInstanceBuilder()
        .caseDefinitionKey("claim-case")
        .businessKey(claimId.value())
        .variable("claimId", claimId.value())
        .start()
        .getId();

// 知识工作者按需启动预注册动作（P11 路线 3）：enabled 状态的 plan item
var discretionary = cmmnRuntimeService.createPlanItemInstanceQuery()
        .caseInstanceId(caseInstanceId)
        .planItemInstanceStateEnabled()
        .list();
cmmnRuntimeService.startPlanItemInstance(discretionary.get(0).getId());

// 里程碑查询（P2）：引擎落 ACT_CMMN_RU_MIL_INST
cmmnRuntimeService.createMilestoneInstanceQuery()
        .caseInstanceId(caseInstanceId).list();
```

定义骨架（`.cmmn.xml`）——哨兵（P12）+ 阶段（P2）：

```xml
<case id="claim-case">
  <casePlanModel>
    <stage id="investigation" name="调查阶段">
      <planItem id="pi-review" definitionRef="reviewSupplement"/>
      <!-- 哨兵条件：ifPart 的条件是子元素 <condition> 的元素文本；
           Flowable CMMN 转换器不解析属性写法，写成属性会被静默忽略（哨兵失效） -->
      <sentry id="docsComplete">
        <ifPart><condition>${documentsComplete}</condition></ifPart>
      </sentry>
      <planItem id="pi-approve" definitionRef="approveClaim">
        <entryCriterion sentryRef="docsComplete"/>   <!-- 条件齐备才可进入 -->
      </planItem>
      <humanTask id="reviewSupplement" name="复核补充材料" flowable:candidateGroups="claim-officer"/>
      <humanTask id="approveClaim" name="核赔审批" flowable:candidateGroups="claim-approver"/>
    </stage>
  </casePlanModel>
</case>
```

Plan item 生命周期（排障必读）：`available → enabled →（手工/自动 start）in progress → completed / terminated`；人工任务落同一张 `ACT_RU_TASK`，收件箱查询与 BPMN 无差异（I1 代码通用）。CMMN 人工任务事件同样可经引擎事件监听器桥接（组件 1/2 复用）。

### I8 运行期干预与定义版本迁移【通用】

```java
// 运行期改道（异常处置、人工纠偏）：move 语义，不是任意插节点
runtimeService.createChangeActivityStateBuilder()
        .processInstanceId(processInstanceId)
        .moveActivityIdTo("investigate", "manualReview")
        .changeState();

// 定义版本演进（expand-and-contract，主文档 7.3 纪律）：在途实例迁移到新版本
// 旧→新节点显式逐点映射，未声明的活动随迁移自动对齐同 id 节点
// 注意：迁移 API 在 ProcessMigrationService（独立引擎服务），不在 RuntimeService；
// 映射用 ActivityMigrationMapping 静态工厂，无 builder
var mapping = ActivityMigrationMapping.createMappingFor(
        "investigate", "reinvestigate");    // 新版本中更名/重构的节点
processMigrationService.createProcessInstanceMigrationBuilder()
        .migrateToProcessDefinition(newDefinitionId)
        .addActivityMigrationMapping(mapping)
        .migrate(processInstanceId);
```

纪律：变更前用 `createProcessInstanceQuery()` 核查在途实例；迁移映射显式逐节点声明；灰度按实体维度分别执行（本仓库两实体独立库 → 独立迁移窗口）。运行期 move 改道对两类系统都是**异常纠偏手段**，不进设计（Onboarding 的常规恢复路径见 P16）。

### I9 测试模式【通用】

| 模式 | 本仓库落点 | Case / Onboarding 场景延伸 |
| --- | --- | --- |
| 装配冒烟（定义唯一 + delegate 全装配 + 拓扑断言） | `Alpha/BetaProcessAssemblySmokeTest`（7.4） | 每类型模板一份冒烟；CMMN 同理校验 plan item 全装配 |
| 独立 H2 库防引擎抢 Job | app 测试 `@TestPropertySource` 唯一库名 | CMMN 引擎与 BPMN 引擎共存时**同样必要**——CMMN 无独立 Job 表，双引擎各自的 AsyncExecutor 共享 `ACT_RU_JOB`/`ACT_RU_TIMER_JOB` 同表抢 Job |
| 时序类断言（SLA/消息） | Awaitility | 定时器用可注入的短时长 + `await()`，不 `Thread.sleep` |
| 契约基类 | `PricingPolicyContractTest`（test-jar 分发） | 分派/triage/门禁策略同样写契约基类，`@ForEntity` 对齐守护 |
| e2e 闭环 | async 通知任务 e2e（7.3③） | 认领→完成→结束投影全链路断言；Onboarding 补「KYC→门禁→激活」闭环与 P16 失败分支断言 |

### I10 可观测与运维【通用】

- **指标**（复用组件 1/4 落地形态）：事件 Counter（type/processDefinitionKey/entity）之外，Case 系统增加**里程碑达成 Counter** 与 **SLA 违约 Counter**，Onboarding 系统增加**阶段转化/流失 Counter**（每阶段进入与放弃事件）与**端到端激活时长**指标——漏斗是 Onboarding 的第一看板；`flowable.deadletter.jobs.count` Gauge 沿用（死信 = 案例卡死/入驻卡死的前兆）。
- **告警分维度**：活跃案例数、超 SLA 挂起数按 entity 分别配置阈值（README 运维纪律）。
- **历史粒度**：`flowable.history-level` 默认 `audit` 足够；`full`（变量级历史）仅排障期临时开——它是 `ACT_HI_VARINST` 膨胀的第一原因。

---

## 七、反模式清单（AM1~AM11）

| # | 反模式 | 后果 | 正确姿势 |
| --- | --- | --- | --- |
| AM1 | 巨型意大利面 BPMN（枚举所有可能路径） | 分支指数增长、改一处动全身 | 动态性建模进事件子流程/多实例，或上 CMMN（1.3 判据） |
| AM2 | 流程变量放大对象/领域聚合 | 历史表膨胀、脏读旧快照、序列化脆弱 | 变量只放轻量标识（P1） |
| AM3 | 状态机画进 BPMN（业务状态 = 图位置） | 状态查询压引擎、离线场景无法收敛 | 领域状态机 + 引擎编排分离（D3） |
| AM4 | Controller/Service 直调 `TaskService` | 引擎锁死、换引擎不可能 | 引擎 API 不出基础设施层（D1） |
| AM5 | BPMN 条件表达式里堆业务规则 | 规则不可测不可管，表达式膨胀 | 结构化决策下沉 DMN（I5），复杂判断回领域 |
| AM6 | 运行期反射改图/改定义 | 引擎内部契约破坏、升级即炸 | 只用官方干预 API（I8），动态性事先建模（P11） |
| AM7 | 事务内同步调下游/发消息 | 回滚后脏消息、长事务拖垮连接池 | 领域事件 AFTER_COMMIT + Outbox（D4/D6） |
| AM8 | 收件箱每请求直查引擎大表 | 高频列表拖垮 `ACT_RU_*` | 投影表承载查询（D7），引擎做点查 |
| AM9 | 忽略 businessKey/幂等键 | 重复实例、MQ 重投产生平行实例 | P8 全套幂等防线 |
| AM10 | 引擎版本升级不做 ACT_* 差异核对 | 启动失败或静默 schema 漂移 | Flyway 管 schema + 升级人工核对四类 schema（含 IDM；引 CMMN 后含 `ACT_CMMN_*`） |
| AM11 | 给路径确定的 Onboarding 上 CMMN/动态构造（事件子流程插入、Discretionary Task） | 过度设计：动态性与双引擎的运维成本白付，图更难读、排障面翻倍 | 路径确定就画确定的图（P14~P16）；CMMN 判据只对 Case 适用（1.3） |

---

## 八、与本仓库骨架的映射与落地路线

### 8.1 已有落点（两类系统的地基已具备）

先说 Onboarding 的直接答案：本仓库 `order-approval`（两实体同 key、拓扑固定、随 profile 裁剪）**就是 Onboarding 类「路径确定」系统的完整样板**——P14 门禁 = 现有网关 + 变量条件的延伸，P15 并行清单 = 多实例（I4）扩展，P16 重试 = 引擎级重试（已落地）+ 显式失败分支。接入真实 Onboarding 业务模块无需新地基。下表对两类系统通用：

| 模式 | 本仓库落点 |
| --- | --- |
| P1 / D2 | `OrderApprovalService`（契约 key 发起、轻量变量、同事务） |
| P3 / D5 | `TaskAssignmentListener` + `TaskAssignmentRule`（`@ForEntity` 契约测试守护） |
| P7 / D4 | `FlowableEngineEventListener` + `FlowableProcessEvent` + AuditPort（AFTER_COMMIT） |
| P8 | businessKey 启动 + Outbox（`OutboxRelay`，ShedLock 防重） |
| P4 / I3 / P16 | `failedJobRetryTimeCycle`、引擎级重试（`FlowableJobProperties`）——SLA 边界事件未用，重试机制与 P16 同源 |
| D1 | `infrastructure.engine` 适配层 + ArchUnit onion 守护 |
| D8 | `EntityContextAwareDelegate` + 装配冒烟（7.4） |
| I9 / I10 | 独立 H2 库测试纪律 + `DeadLetterJobOperations` Gauge |

### 8.2 Onboarding 落地路线（接入真实业务模块时）

无新地基缺口——在 `order-approval` 式样板上直接扩展门禁网关（P14）、多实例清单（P15）、错误边界事件（P16）；实体差异化入驻流程即 P5 的双实体同 key 不同拓扑（已验证）。需要补的只有两件：P9 收件箱端口（若有人工环节）与 D7 进度投影（漏斗看板的数据源）。

### 8.3 Case 落地路线（接入真实业务模块时）

1. **收件箱端口与投影**（P9 + D7）：`CaseTaskQueryPort`（窄接口）+ 任务事件投影表——收件箱是 Case 系统第一个用户可见面，先于 CMMN。
2. **事件子流程动态插入样板**（P11 路线 1 + I6 Case 用法）：在现有 `order-approval` 上加一个消息触发子流程验证全链路（消息契约、关联、幂等）。
3. **SLA 边界事件 + 违约指标**（P4 + I3 + I10）：可注入时长配置 → 两实体差异化时限（`application-{entity}.yaml`）。
4. **DMN triage**（I5 + D5）：确认 `flowable-dmn` starter 的 SB4 兼容后引入，规则表进实体模块随 profile 裁剪。
5. **CMMN 评估**（I7）：仅当出现"动作集合开放"的真自由案例类型时启动；引入即复制 7.3 全套治理（Flyway DDL 提取、Enforcer 锚定、升级核对、装配冒烟扩展到 plan item）。

> 一句话总结：**两类系统共享同一地基——"流程编排器退化 + 领域状态机 + 事件驱动审计"（本仓库已具备）；Onboarding 在地基上加确定性编排模式（P14 门禁、P15 并行清单、P16 失败重试），路径即图、不加动态构造；Case 按"BPMN 动态构造优先、CMMN 兜底真自由场景"的顺序引入动态性。所有引擎能力经适配层窄端口暴露，所有规则实体化为策略扩展点。**

---

### 主要来源

- Flowable 官方文档：[CMMN 引擎章节](https://www.flowable.com/open-source/docs/cmmn/ch01-Introduction)、[BPMN 章节](https://www.flowable.com/open-source/docs/bpmn/index)
- OMG：[CMMN 1.1 规范](https://www.omg.org/spec/CMMN/1.1/)
- microservices.io：[Saga](https://microservices.io/patterns/data/saga.html)、[Process Manager](https://microservices.io/patterns/data/process-manager.html)
- Camunda：[CMMN 1.1 参考（Camunda 7，已 EOL）](https://docs.camunda.org/manual/latest/reference/cmmn11/)——作为路线对照
- 本仓库：主文档 7.1~7.4 / 8.1、《BPMN与Flowable8完全指南》、《SpringBoot-Flowable通用公共组件调研》
