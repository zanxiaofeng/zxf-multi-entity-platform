# 商业银行对公客户准入流程——BPMN 建模与 Flowable 实现方案

> **文档定位**：本文聚焦对公客户准入的 **BPMN 建模层**（泳道/Stage/节点/网关）与 **Flowable 8 落地实现**（引擎属性、多实例、DMN 集成、部署结构）。监管依据全景、系统架构、数据模型与代码骨架见《[商业银行客户准入系统设计文档](商业银行客户准入系统设计文档.md)》（下称「系统设计文档」）；BPMN/DMN 元素级语义见《[BPMN 2.0 / DMN 与 Flowable 8 完全指南](BPMN-DMN与Flowable8完全指南.md)》（下称「完全指南」）。本文与两份基线文档冲突之处已按其口径修订，修订记录见第八章。
>
> **版本基线**：Flowable 8.0.x（8.2-D1 已决：维持 8.0.x，不升 8.1；本仓库 Enforcer 锚定 `[7.0.0,8.0.0)`，ACT_* 表归 Flyway 管理，未来任何版本升级均须人工核对四类 schema 差异——common/process/idm/eventregistry）。

## 一、总体架构

### 1.1 流程结构

主流程：银行对公客户准入流程（BPMN Process），**一张主图只服务对公一类客户**（系统设计文档 4.1.2 纪律；零售/同业准入差异另行拆分定义）。

泳道按部门/角色划分，Pool 内设置以下 Lane（Flowable 中 Lane 仅为图形信息、无执行语义，运行时任务归属由 assignee/candidateUsers/candidateGroups 决定）：

- 客户经理（RM）
- 合规/反洗钱岗
- 审批岗
- 运营/开户岗
- 系统自动处理

子流程（**5 个阶段、6 个子流程定义，统一以 Call Activity 调用**——S3 拆为 S3a/S3b 并行执行）：

- S1 受理与资料预检
- S2 尽调与 KYC
- S3a 筛查与风险评级
- S3b 准入规则校验
- S4 审批决策
- S5 签约、开户与归档

**阶段拆分为 Call Activity 而非内嵌 Collapsed Sub-Process（8.2-D2 已决）**：内嵌子流程与主流程同一定义、共享变量，主图折叠后观感清晰，但六个阶段内嵌会使单定义体量巨大——系统设计文档 4.1 基于实例迁移脆弱性（活动映射逐个对齐）确立「拒绝巨型图」纪律。Call Activity 拆分的收益是变更隔离：单阶段规则调整只影响该子流程定义及其引用的 DMN，主流程与其余阶段零回归；每个子流程可独立版本化。代价与配套纪律：跨定义观测以 businessKey（准入申请编号）串联，引擎不保证 businessKey 唯一，幂等须在业务表自建唯一索引；子流程不继承主流程变量，经 `flowable:in`/`flowable:out` 显式映射所需输入与结论输出——与第五章「变量只存轻量引用」口径一致，映射面即接口契约，宜少而精。

独立流程（Call Activity 或 Message Start Event 触发）：

- P1 拒绝与申诉
- P2 存续期管理
- P3 AML 合规上报

### 1.2 流程启动方式

系统仅面向银行员工使用，流程由客户经理登录系统发起，采用 None Start Event。若信贷系统或核心系统推送准入申请，则改用 Message Start Event（None + Message 双启动事件并存时，分别经 `startProcessInstanceByKey` 与消息投递触发，二者共存合法，但 BPMN 校验要求所有启动事件均有定义）。

### 1.3 泳道设计原则

**按角色/部门划分泳道，按阶段拆分子流程。** Lane 表示责任主体，回答「谁负责」；Stage 用 Sub-Process 表示流程进度，回答「走到哪一步」。两者维度不同，不应混用。

按阶段划分泳道不符合 BPMN Lane 的标准语义。阶段不是参与者，而是流程进度。强行把阶段做成 Lane，会导致同一角色跨多个 Lane、同一 Lane 内混合多个角色，责任边界模糊。在 Flowable 中，Lane 不参与运行时任务分配，真正决定任务归属的是 assignee、candidateUsers、candidateGroups。按角色划分泳道，Lane 与 candidateGroups 自然对应，模型图与运行时权限一致，审计时也能直接看出责任部门。

**系统自动处理的 Task 放在独立的「系统自动处理」泳道。** 系统也是责任主体，不应塞进任何人类角色泳道。该泳道通常放在 Pool 的最下方，与人类角色泳道并列。所有 Service Task、Business Rule Task、Send Task、Message Catch Event 均放入此泳道（本方案外部系统调用统一异步 ServiceTask，不使用 External Task，见 8.2-D3）；User Task 和 Manual Task 放入对应角色泳道。

**防止流程图混乱的做法。** 单独设系统泳道本身不会导致混乱，混乱来自 Stage 不折叠、系统任务碎片化、人机频繁交替。对应措施：

- S1–S5 以 Call Activity 建模，主图只显示调用节点，子流程内部展开才看到泳道交互。
- 同一阶段的系统任务连续成组，中间不插人工任务，减少跨泳道跳转次数。
- 合并细粒度系统调用：同一阶段、同一目的的外部接口调用合并为一个系统任务节点，内部并行由实现层处理，不占用模型节点。
- 同一决策点的规则校验合并建模，规则仍分表维护（多表编排方式见 2.6 节修订说明）。

这样主图呈现「上方人工、下方系统、阶段折叠」的稳定结构，责任清晰，自动化范围一目了然。

## 二、Stage 与 Task 详细规划

### 2.1 S1 受理与资料预检

| Task | 类型 | 泳道 | Flowable 实现 |
| --- | --- | --- | --- |
| 发起准入申请 | User Task | 客户经理 | `flowable:assignee="${initiator}"`（引擎自动注入认证用户），表单采集客户类型、业务品种、申请额度 |
| 资料收集与预检 | User Task + Business Rule Task | 客户经理 + 系统自动处理 | User Task 上传证照与授权文件；Business Rule Task 调用 DMN「资料清单匹配」决策表（hit policy = COLLECT，多维度资料要求汇总） |
| 补件 | User Task | 客户经理 | Exclusive Gateway 判断资料是否齐全，不全则回退至此 Task，完成后回到预检 |

DMN 决策表 `document-checklist`：

| 输入 | 输出 |
| --- | --- |
| 客户类型（对公/集团） | 所需资料清单 |
| 业务品种（开户/授信/贷款/理财/托管/国际业务） | 资料必填项列表 |

客户类型、业务品种在发起表单中一次采集，不单独建 Task。补件循环用 Exclusive Gateway 回退，形成明确的 Loop——阶段已拆为 Call Activity，回退发生在 S1 子流程内部，不污染主图；若补件路径继续膨胀（多轮补件、客户侧等待计时），再抽 sub-supplement 独立子流程（系统设计文档表 4-1 口径）。申请额度在 S1 即采集，作为 S3b 准入规则校验的输入。

客户类型枚举为**对公/集团**——原文的「同业」已剔除（8.2-D5 已决）：同业机构准入的尽调口径与审批链路差异较大，独立流程定义承接，不并入本流程。

### 2.2 S2 尽调与 KYC

| Task | 类型 | 泳道 | Flowable 实现 |
| --- | --- | --- | --- |
| 身份与唯一性核验 | 异步 ServiceTask（`flowable:async="true"`） | 系统自动处理 | 并行调用工商、公安、客户主数据系统，防重复建档；`flowable:failedJobRetryTimeCycle="R3/PT10M"` 退避重试，耗尽转死信队列人工干预 |
| UBO 备案核对 | 异步 ServiceTask | 系统自动处理 | 对接人民银行受益所有人信息查询管理系统核对备案信息（12 号令〔2025〕第 23 条）；重大差异触发差异报告分支（30 个工作日内提交，第 27 条） |
| 对公结构尽调 | User Task | 客户经理 + 合规复核 | UBO 穿透识别、实际控制人、关联方、集团客户识别，强制填写识别路径说明并上传佐证材料；识别结论双人留痕（8.2-D6 已决） |
| 经营真实性与开户意愿核实 | User Task | 客户经理 | 现场核实、面签、双录、附件上传（基本户须向法定代表人核实意愿，《企业银行结算账户管理办法》第 9 条） |

**UBO 识别的合规要求。** 对公结构尽调必须遵循《金融机构客户受益所有人识别管理办法》（中国人民银行令〔2025〕第 12 号，2026-01-20 起施行）的三类识别标准：一是通过直接或间接方式最终拥有法人、非法人组织 25% 以上股权、股份或合伙权益的自然人；二是虽未满足标准一，但最终享有 25% 以上收益权、表决权的自然人；三是虽未满足标准一，但单独或联合对法人、非法人组织进行实际控制的自然人（包括但不限于通过协议约定、关系密切的人等方式实施控制，例如决定法定代表人、董事、监事、高级管理人员的任免，决定重大经营、管理决策的制定或执行，决定财务收支，长期实际支配使用重要资产或主要资金）。

尽调表单中必须强制包含以下字段：

UBO 权利状况信息：

- 受益所有权关系类型（股权/收益权/表决权/实际控制）
- 形成时间与终止时间
- 股权比例、收益权/表决权比例或实际控制方式说明
- 识别路径与推理过程说明（必填）

佐证材料：

- 股权结构图、公司章程、股东名册
- 协议控制或代持情形下的相关协议文件
- 无法穿透时的替代判断理由

**禁止仅依赖系统查询。** 12 号令第 4 条明确要求金融机构不得仅通过查询受益所有人信息查询管理系统的方式获取受益所有人信息，或者直接通过批量化、自动化等辅助手段代替必要的风险判断和受益所有人识别核实。因此，对公结构尽调设计为 User Task 是正确选择，但表单不能只是让客户经理填写系统查询结果，必须要求判断推理过程和佐证材料上传。UBO 识别结论与系统设计文档 4.2.2 口径对齐（8.2-D6 已决）：客户经理判断 + 合规岗复核，双人留痕；S3a 的合规复核则聚焦名单命中处置与高风险评级审定，两者职责不重叠。

**所有权结构复杂性的风险标记。** 当存在以下情形之一时，尽调表单应自动标记为「结构复杂」并触发 S3a 风险评级中的加强措施：

- 所有权结构涉及境外持股且无法核实登记注册信息
- 存在循环嵌套、交叉持股
- 存在协议控制、代持
- 所有权结构出现异常变化（如频繁更换法定代表人且无合理理由）

上述情形属于反洗钱监管中明确的高风险特征，应触发强化尽职调查（EDD）。EDD 措施执行与高级管理层批准（11 号令第 28 条第五项「需获得高级管理层的批准」为硬义务）已补入 S3a 高风险分支（8.2-D6 已决，见 2.3 表格的 sub-edd 行）。

**外部系统调用统一异步 ServiceTask（8.2-D3 已决）。** 外部系统调用一律建模为异步 ServiceTask（`flowable:async="true"`）：async job 失败后转带冷却时间的 timer job 重试、重试耗尽（默认 3 次）移入 ACT_RU_DEADLETTER_JOB 死信表，经死信监控人工干预重放（系统设计文档 6.3 口径）；技术异常（超时、宕机）与业务拒绝（核查不通过）严格分流——后者建模为 BPMN Error 边界事件走业务分支，业务拒绝不重试、直接转人工。曾评估 External Task（Worker 拉取）风格：引擎侧重试/死信/Error 边界语义等价，但需建设长轮询 Worker 与锁管理基建，且与仓库既有死信运维割裂——无非 JVM Worker 硬需求，不采用（决策记录见 8.2-D3）。对公结构尽调中的 UBO、实控人、关联方可在同一 User Task 内通过子表单/多层级录入实现，不必拆成多个 Task。

### 2.3 S3a 筛查与风险评级

| Task | 类型 | 泳道 | Flowable 实现 |
| --- | --- | --- | --- |
| 名单与舆情筛查 | 异步 ServiceTask | 系统自动处理 | 并行筛查企业、UBO、股东、董监高、经办人、担保人的制裁/PEP/负面舆情，记录名单版本号 |
| 筛查处置 | Business Rule Task | 系统自动处理 | 调用「名单命中分级处置」DMN（hit policy = FIRST，兜底行置底） |
| 风险评级 | Business Rule Task | 系统自动处理 | 调用「风险评级」DMN（hit policy = FIRST，最严规则置顶 + 兜底行；不用 UNIQUE——评级维度天然重叠，UNIQUE 要求规则完全不相交，细则微调易漏行） |
| 强化尽调（EDD） | Call Activity（sub-edd） | 客户经理 / 高级管理层 | 高风险或「结构复杂」标记触发：执行 11 号令第 28 条强化尽调措施，第五项「高级管理层批准」落为高管会签 User Task（多实例并行）；完成后携结论入 S4（8.2-D6 已决） |
| 合规复核 | User Task | 合规/反洗钱岗 | 仅名单命中或高风险触发，高风险双人复核（UBO 识别复核已在 S2 落实，此处聚焦名单处置与评级审定） |

DMN 决策表 `screening-disposition`（名单命中分级处置）——不能仅按「命中/未命中」二元判断，必须按名单类型分流处置：

| 名单类型 | 命中处置 |
| --- | --- |
| 制裁名单（联合国安理会决议、外交部定向金融制裁名单；恐怖活动组织及人员名单按制裁口径处置——银反洗发〔2018〕19 号第 48 条为五类名单库，表型划分须按其逐类对齐） | 立即停止提供金融服务，限制资金资产转移，触发 P3 AML 合规上报（命中当日），不得通知客户 |
| PEP 名单 | 加强尽职调查，不自动拒绝，需合规岗审定并报高级管理层批准 |
| 负面舆情 | 人工判断，可要求补充说明或加强尽调 |
| 内部黑名单 | 按内部政策处置，可能拒绝或限制产品 |

制裁名单的处置依据《反洗钱特别预防措施管理办法》（原文标注 2026-02-16 起施行；**文号与施行日期以正式发布文本核对后回填**——该文件未收录于系统设计文档表 2-1 监管清单〔截至 2026-09〕，8.2-D4），对名单所列对象应立即停止提供金融等服务或资金、资产，立即限制相关资金、资产转移。该处置与 PEP 命中有本质差异，不可统一处理；处置语义本身（立即停止、限制转移、不通知客户）与银反洗发〔2018〕19 号第 49/50 条及新《反洗钱法》一致，不因文号核对而悬置。

DMN 决策表 `risk-rating`：

| 输入 | 输出 |
| --- | --- |
| 企业风险分 + UBO 风险分 + 行业风险等级 + 区域风险等级 + 业务品种 + 所有权结构复杂性标记 | 洗钱风险等级（低/中/高） |

所有权结构复杂性作为独立风险因子纳入决策表输入。高风险触发双人复核。输入表达式语言为 JUEL（Flowable DMN 不支持 FEEL，见完全指南 20.2），决策表须以 `-` 全匹配规则兜底保证表完备，避免空结果进入下游分支。

**审计证据要求。** S3a 筛查环节必须记录名单版本号，作为审计证据。制裁名单可能一天更新数次，PEP 名单按月调整，监管通报不定期下发，准入阶段的筛查必须使用最新版本的名单。

### 2.4 S3b 准入规则校验

| Task | 类型 | 泳道 | Flowable 实现 |
| --- | --- | --- | --- |
| 准入规则校验 | Business Rule Task（多节点串联，见下） | 系统自动处理 | 行业政策、区域政策、额度适配、产品适配、黑名单比对、例外审批触发分别建 DMN 表，各表由对应业务条线维护 |

DMN 决策表按业务条线拆分，每张表由对应业务条线维护，降低单表复杂度，提高规则变更的响应速度。

> **修订（原文错误）**：原文称「模型上合并为一个 Business Rule Task 节点，内部顺序调用多张 DMN」。**Flowable DMN 不支持决策链/DRG（DRD）**（完全指南 20.2 支持矩阵明确 ❌），单个 Business Rule Task 只能执行一张决策表。多表编排的两种合规做法：
>
> 1. **BPMN 多个 Decision Task 串联**（主流，完全指南 20.3/23 推荐）：六张表拆为顺序相连的多个 Business Rule Task 节点，前表输出变量即后表输入——节点增多但每表独立版本化、独立演进；
> 2. **decisionService**（DMN decisionService 元素，Java 侧 `ExecuteDecisionBuilder.executeDecisionService()`）一次调用齐取多张表，适合表间无依赖、只需并行取结果的场景。
>
> 若嫌串联节点多，可按目的归并（如「行业+区域政策」合成一张表、输入列扩展），但**表由业务条线独立维护**的治理诉求优先于图面简洁。

S3a 侧重身份维度的风险（名单、舆情、风险评级），S3b 侧重业务维度的适配性（行业、区域、额度、产品），两者**并行执行**：Parallel Gateway 分叉，S4 前汇聚（join 等待两路齐备——S4 审批路由同时依赖 S3a 的风险等级与 S3b 的校验结果）。

### 2.5 S4 审批决策

| Task | 类型 | 泳道 | Flowable 实现 |
| --- | --- | --- | --- |
| 审批路由 | Business Rule Task | 系统自动处理 | DMN 输出审批层级、审批人员集合、会签规则参数 |
| 审批任务 | User Task（Multi-Instance） | 审批岗 | 按 DMN 输出的集合动态创建审批实例 |
| 超时升级 | Boundary Timer Event | 审批岗 | 绑定审批 User Task，`timeDuration` 审批 SLA（如 PT24H），超时触发转报上级 |
| 条件落实 | Call Activity | 审批岗 | 有条件通过后触发独立子流程 |

DMN 决策表 `approval-routing`（hit policy = FIRST + 兜底行）：

| 输入 | 输出 |
| --- | --- |
| 金额区间 + 风险等级 + 产品类型 + 是否集团客户 + 是否跨境 | 审批层级（一级/二级/贷审会）/ 审批人员集合 / 会签规则参数（`passThreshold`：通过阈值；`vetoEnabled`：一票否决开关） |

一级、二级、会签、贷审会不必各画一个节点，统一抽象为「审批任务」，通过 DMN 决定审批层级、人员集合、会签规则。

**多实会计票的关键约束。** Flowable 多实例的 completionCondition 基于完成实例数，内建变量（`nrOfInstances`/`nrOfActiveInstances`/`nrOfCompletedInstances`）只统计「完成数」，不区分同意与否（完全指南 16.4）。赞成票、反对票、弃权、权重、动态加签、审批意见和责任证据仍应由审批领域服务定义。

**修订（原文缺陷）**：原文给出的固定表达式 `${approveCount > nrOfInstances / 2 || rejectCount > 0}` 只覆盖「多数通过 + 一票否决」，无法表达 S4 DMN 输出的三种会签规则。修正为**规则参数驱动**——DMN 不输出规则文本，输出数值化参数；进入多实例前由领域服务按人员集合规模换算出确定阈值，completionCondition 只消费变量：

```xml
<multiInstanceLoopCharacteristics isSequential="false"
    flowable:collection="${approvalService.resolveApprovers(execution)}"
    flowable:elementVariable="approver">
  <completionCondition>
    ${(vetoEnabled && rejectCount > 0) || approveCount >= passThresholdCount}
  </completionCondition>
</multiInstanceLoopCharacteristics>
```

三种规则的统一映射：**全部通过** = `vetoEnabled=true` + `passThresholdCount=人数`；**多数通过** = `vetoEnabled=false` + `passThresholdCount=过半数`；**一票否决** = `vetoEnabled=true` + `passThresholdCount=人数`（任一反对即提前结束）。弃权是否计入分母，由领域服务计算 `passThresholdCount` 时按行内制度决定，模型层不感知。

配套纪律：

- 计票在 TaskListener（complete 事件）或领域服务中累加 `approveCount`/`rejectCount`/`abstainCount` 流程变量。并行多实例各实例完成事务对同一实例级变量存在乐观锁竞争，引擎自动重试但高并发下有重试噪声——计数更新须短小，或改由 ExecutionListener 在实例完成回调统一聚合。
- completionCondition 提前结束时，剩余未完成实例连同待办被引擎删除（触发 delete 事件）：须在 delete 监听中对待办人发通知并留痕「因会签规则已满足而失效」，避免审批人视角任务凭空消失。
- `resolveApprovers()` 方法必须声明为 public、参数含 DelegateExecution（Spring Bean 表达式调用要求），方法内捕获所有业务异常并 fallback 到默认审批链。
- 动态加签不是运行时增加多实例实例数（`nrOfInstances` 在到达时已定）；加签应建模为领域服务扩展审批链并重算阈值，或终止当前多实例重启。

超时升级绑定在审批 User Task 上，用中断型 Boundary Timer Event（完全指南 16.5），超时后流向升级节点。

### 2.6 S5 签约、开户与归档

| Task | 类型 | 泳道 | Flowable 实现 |
| --- | --- | --- | --- |
| 签约与通知 | User Task + Send Task | 运营/开户岗 + 系统自动处理 | 审批通过后即通知客户，不必等开户完成 |
| 开户指令与回写 | 异步 ServiceTask + Message Catch Event | 系统自动处理 | 异步 ServiceTask 调核心系统（幂等键=申请编号+账户类型，业务表唯一索引兜底）；Message Catch Event 等待异步回执 |
| 归档 | Service Task | 系统自动处理 | 归档至影像系统，落业务库留档 |

开户回写若用 Receive Task，流程实例会长期挂起。用异步 ServiceTask 处理，或 Message Catch Event 配合消息关联（`messageEventReceived` 按 message name + correlation 变量定位实例）过滤事件，避免流程实例长期占用。Message Catch Event 需设置超时边界事件，防止无限期挂起。

> **修订**：原文「归档 Service Task + Data Store，Data Store 持久化」——Flowable 开源版对 Data Store/Data Object 仅可解析、运行时语义弱（完全指南 §11），持久化不成立。归档落地为 Service Task 调影像系统 + 业务库留档，Data Store 仅作建模标注。

## 三、独立流程

### 3.1 P1 拒绝与申诉

| Task | 类型 | 泳道 | 实现 |
| --- | --- | --- | --- |
| 拒绝原因登记 | User Task | 客户经理 | 结构化拒绝码 + 说明 |
| 通知客户 | Send Task | 系统自动处理 | 仅普通拒绝；制裁/PEP 命中不通知（反洗钱保密义务） |
| 申诉受理与复审 | User Task | 合规/反洗钱岗 | 独立申诉子流程 |
| 流程终止 | None End Event | — | 普通拒绝用 None End Event，不用 Terminate |

### 3.2 P2 存续期管理

| Task | 类型 | 泳道 | 实现 |
| --- | --- | --- | --- |
| 定期重检提醒 | Intermediate Timer Event | 系统自动处理 | 按周期触发（实例粒度建议：每客户一个常驻实例，由 businessKey 关联） |
| 名单持续监控 | 异步 ServiceTask | 系统自动处理 | 批处理筛查，记录名单版本号 |
| 风险等级动态调整 | User Task | 合规/反洗钱岗 | 人工审定 |
| 触发重新准入 | Message Start Event | 系统自动处理 | 启动新准入流程实例 |

> **修订**：原文「Link Event 只能在同一流程内跳转，不能跨流程实例」理由不准确——Flowable 中 Link Intermediate Event **无执行语义**（完全指南 §11），在可执行模型中根本不可用。重新准入正确做法即原文结论：Message Start Event 启动新流程实例，或 Call Activity 调用准入流程。

存量客户批量重检（11 号令第 51 条：施行起半年内完成较高风险以上存量尽调、2 年内完成全部）由 P2 承载，是 2026—2028 年的主要批量任务来源。

### 3.3 P3 AML 合规上报

| Task | 类型 | 泳道 | 实现 |
| --- | --- | --- | --- |
| 合规人工审核 | User Task | 合规/反洗钱岗 | 合规岗审核可疑线索 |
| AML 系统上报 | 异步 ServiceTask | 系统自动处理 | 调用 AML 系统，保密处理 |
| 冻结/处置 | User Task | 合规/反洗钱岗 | 合规岗执行冻结指令 |
| 流程终止 | None End Event | — | 上报完成后正常结束 |

AML 上报为独立合规流程，由 S3a 或 S4 触发（名单命中当日上报，银反洗发〔2018〕19 号第 50 条），人工审核、保密处理，不通知客户。

## 四、Flowable 关键配置要点

1. **DMN 独立部署、独立版本化。** 决策表与流程**分开部署**（DMN 资源独立目录部署，改规则不发流程、改流程不改规则——完全指南 23 第 2 条，这是引入 DMN 的核心收益，也是监管细则密集换版下「规则必须外置」的系统设计文档结论）。Business Rule Task 通过 **`flowable:decisionTableReferenceKey`** 引用决策表 key（原文误写 `decisionTableKey`；多命中结果构造成 JSON 数组存入以决策 key 命名的变量，`flowable:resultVariableName` 在 DMN 路径被忽略勿写；无命中兜底二选一：`-` 兜底规则或 field `decisionTaskThrowErrorOnNoHits` 把表不完备暴露为显式失败）。

2. **外部系统调用统一异步 ServiceTask（8.2-D3 已决，不使用 External Task）。** 每个调用需定义业务幂等键（如「申请编号+查询类型+日期」），在实现端基于幂等键去重；重试策略区分瞬时故障（网络超时，可重试）与业务故障（客户不存在，应直接失败走 Error 边界分支），经 `flowable:failedJobRetryTimeCycle` 配置（格式 `R3/PT10M`：重试 3 次、间隔 10 分钟）；死信队列须配监控告警与人工重放通道（`ManagementService.moveDeadLetterJobToExecutableJob`），死信不是自动恢复机制。

3. **异步执行的事务边界。** 开启 async 后，引擎提交当前事务、创建异步作业、立即返回控制权，由 Async Executor 在新事务中执行。失败不会回滚之前已完成的工作，引擎自动重试（async job 失败转 timer job 冷却，重试耗尽入死信）。

4. **多实会计票外置。** 赞成/反对/弃权在 Task Listener 或领域服务累计为流程变量，会签规则经 DMN 输出参数（`passThreshold`/`vetoEnabled`）驱动变量化完成条件（见 2.5 修订）。`resolveApprovers()` 必须声明为 public 且参数含 `DelegateExecution`，方法内捕获所有业务异常并 fallback。

5. **任务监听器统一 SLA 与审计。** 用 BpmnParseHandler 在部署时自动为所有 User Task 挂载 Task Listener，统一实现：任务创建时计算 dueDate、记录操作日志、发送待办通知，避免在模型里逐个手工添加（与系统设计文档 7.3 全局事件监听同一机制）。

6. **审计日志与留存期限（修订）。** History Level 设为 `full` 记录所有变量更新详情（HistoricDetail）。**两点原文错误必须纠正**：其一，**留存期限是 10 年不是 5 年**——新《反洗钱法》（2024 修订，2025-01-01 施行）第 34 条与《金融机构客户尽职调查和客户身份资料及交易记录保存管理办法》（〔2025〕第 11 号，2026-01-01 施行）第 44 条均规定至少保存 **10 年**（原文引用的 5 年口径来自 2022 版〔2022〕第 1 号令，该文件从未生效且已于 2026-01-01 明文废止；〔2007〕第 2 号同步废止——系统设计文档表 2-1）；行内政策更严时从其规定。其二，**引擎历史表不能直接当 10 年审计库用**——Flowable History Cleaning 默认删除 365 天前已结束实例的历史，必须关闭/重设清理窗口并按系统设计文档 5.3 三层留存方案落地（引擎轨迹 → 业务审计库 → 归档），操作人/时间/规则版本号/名单版本号/决策结果的审计主记录落业务审计库。敏感字段（用户 ID、部门信息）加密存储，**加密发生在业务库与审计库层**——引擎 ACT_HI_* 表对变量明文记录，这正是第五章要求流程变量只存轻量引用的原因。

7. **边界事件类型匹配。** 定时边界事件配 `timeDuration`（如 PT24H），错误边界事件配 `errorCode`（BPMN Error 与 Java 异常无引擎语义关联，须显式声明抛出），消息边界事件配 `messageRef`。条件表达式必须返回布尔值，变量必须在流程执行上下文中存在。

8. **拒绝不用 Terminate End Event。** 普通拒绝走 None End Event；制裁/PEP 确认命中走独立合规处置流程（P3），确保 AML 报送、冻结、留痕不被跳过。Terminate 留给并行分支需全部终止的场景（`flowable:terminateAll` 可扩至整个实例）。

## 五、流程变量与数据对象

**修订**：原文将客户信息表、UBO 识别记录、KYC 报告等直接列为「流程变量 + 业务库」存储。按系统设计文档 5.2 流程变量规范（与本仓库 README 7.1 口径一致）修订：**流程变量只承担路由与轻量结论**，白名单化管理、单变量不超过 2000 字符；明细、长文本、附件一律落业务表，变量只放引用 ID。禁用 serializable 对象变量（类版本演进后存量实例反序列化失败）与集合型变量承载明细；同一事务链内传递大对象用 transient 变量（不持久化、不留历史，但不可跨等待状态）。这样 ACT_HI_VAR_INST/HistoricDetail 的全量留痕只覆盖轻量结论，敏感明细在业务库加密、按 10 年留存。

| 数据对象 | 内容 | 存储方式 |
| --- | --- | --- |
| 客户信息 | 基本信息、股权结构、UBO | 业务库（加密）；流程变量仅 customerRef（申请编号）等引用 |
| UBO 识别记录 | 识别路径、佐证材料、权利状况信息 | 业务库 + 附件系统；变量仅 uboConfirmed 等结论标志 |
| KYC 报告 | 尽调结果 | 业务库 + 附件系统；变量仅 kycRef |
| 筛查结果 | 名单命中记录、名单版本号 | 命中类型与等级入变量（路由用）；命中明细与版本号入业务库 |
| 风险评级 | 风险分与等级、结构复杂性标记 | riskLevel、structureComplex 等标量入变量；评分明细入业务库 |
| 审批意见 | 审批结论与投票明细 | 投票明细入业务库；approveCount/rejectCount/abstainCount 等计票变量入流程变量 |
| 开户回执 | 核心系统回执 | 消息载荷 → 业务库；变量仅回执标志/账户引用 |
| 操作日志 | 操作人、时间戳、规则版本、名单版本 | 业务审计库（引擎 HistoryLevel=full 轨迹 + 三层留存，见四-6） |

每个 User Task 需定义表单、权限、待办、留痕；每个系统任务节点需定义接口、幂等键、重试策略、降级方案。

## 六、部署结构

```
部署单元（按引擎资源类型分目录，独立版本化）
├── bpmn/                                  # 流程定义（本仓库：实体模块 resources/processes/）
│   ├── onboarding-corp.bpmn20.xml         # 主准入流程（编排 S1-S5 Call Activity 与并行网关）
│   ├── sub-s1-precheck.bpmn20.xml         # S1 受理与资料预检
│   ├── sub-s2-kyc.bpmn20.xml              # S2 尽调与 KYC
│   ├── sub-s3a-screening.bpmn20.xml       # S3a 筛查与风险评级（含 sub-edd 调用）
│   ├── sub-s3b-eligibility.bpmn20.xml     # S3b 准入规则校验
│   ├── sub-s4-approval.bpmn20.xml         # S4 审批决策
│   ├── sub-s5-account.bpmn20.xml          # S5 签约、开户与归档
│   ├── sub-edd.bpmn20.xml                 # EDD 强化尽调（高风险触发，高管会签）
│   ├── reject-appeal.bpmn20.xml           # P1 拒绝与申诉
│   ├── lifecycle.bpmn20.xml               # P2 存续期管理
│   └── aml-report.bpmn20.xml              # P3 AML 上报
├── dmn/                                   # 决策表（独立部署、独立版本化，改规则不发流程）
│   ├── document-checklist.dmn             # 资料清单匹配（COLLECT）
│   ├── screening-disposition.dmn          # 名单命中分级处置（FIRST+兜底）
│   ├── risk-rating.dmn                    # 风险评级（FIRST+兜底）
│   ├── industry-policy.dmn                # 行业政策校验
│   ├── region-policy.dmn                  # 区域政策校验
│   ├── limit-eligibility.dmn              # 额度适配校验
│   ├── product-fit.dmn                    # 产品适配校验
│   ├── blacklist-check.dmn                # 黑名单比对
│   ├── exception-trigger.dmn              # 例外审批触发
│   └── approval-routing.dmn               # 审批路由（FIRST+兜底，输出 passThreshold/vetoEnabled）
└── （表单定义不在部署单元内，见下）
```

> **修订（原文错误）**：原文 BAR 内含 `forms/*.form`（Flowable Form Engine 格式）。**Flowable 7.0 起已移除 Form 引擎与全部 UI 应用**（系统设计文档 1.3.2），Flowable 8 无引擎侧表单渲染——`.form` 文件没有消费方。表单自建：User Task 以 `flowable:formKey` 指向自建表单中心/前端表单标识，表单元数据与权限在业务侧维护；本仓库落地时表单定义随实体模块配置，不放引擎部署。

部署时同 key 重部署版本自动递增（`ACT_RE_PROCDEF.VERSION_`），**存量实例继续走启动时版本**——流程定义变更走 expand-and-contract：删改节点前以 `runtimeService.createProcessInstanceQuery()` 核查在途实例，在途迁移经 `createProcessInstanceMigrationBuilder()` 并先 `validateMigration()`；活动 id 保持稳定（只加不改不删）可大幅降低迁移成本。所有自定义类（JavaDelegate、TaskListener 等）须在引擎 classpath 上。

DMN 侧生产配置 `history-enabled: true` 开启决策执行审计（输入输出快照可追溯）。

## 七、与系统设计文档及本仓库骨架的衔接

**与《商业银行客户准入系统设计文档》的关系**：该文档是对公+零售准入的完整系统设计（监管映射/架构/数据模型/代码骨架）；本文是其对公链路在 BPMN 建模层的细化。两文原差异点已按 8.2 决策对齐：阶段拆分采 Call Activity（D2）、外部调用统一异步 ServiceTask（D3）、补齐 EDD 与 UBO 复核（D6）、同业客户独立定义（D5）；其表 2-1 监管清单、4.1 拆分纪律、4.3.1 hit policy 口径、5.2 变量规范、5.3 三层留存、6.3 集成可靠性模式为本文上位依据。

**若在本仓库（zxf-multi-entity-platform）落地**，另受骨架硬性约束（CLAUDE.md / README「Review 硬性规则」）：

- BPMN/DMN 放实体模块 resources（`processes/`、DMN 独立目录），随 Maven profile 裁剪；app 模块只放公共配置——多实体下若两实体准入拓扑不同，按「同 key（如 onboarding-corp）不同拓扑」惯例拆到各自实体模块，装配冒烟测试守护（同 key 唯一 + delegate 全装配 + 拓扑符合预期）。
- JavaDelegate/系统任务实现一律继承 `EntityContextAwareDelegate`（Job 线程从流程变量重建实体上下文），禁止直接 `implements JavaDelegate`（实体模块 ArchUnit 强制）；专属 delegate 以 `@ForEntity` 限定，通用 delegate 进 core。
- 流程变量额外携带 `entity` 标识（引擎任务经全量 MDC 快照随车传播）；业务库表带 entity 维度。
- ACT_* 表归 Flyway 管（`common/V3`，`flowable.database-schema-update=false`）；升级 Flowable 版本须人工核对 common/process/**idm**/eventregistry 四类 schema 差异补脚本。
- app 测试类各自独立 H2 库（`@TestPropertySource` 覆盖 datasource url），防跨上下文引擎抢 Job。
- 本仓库 README「Flowable 设计文档」索引表同步登记本文。

## 八、评审记录与决策记录

### 8.1 相对原文稿的修订（评审发现，已落入正文）

| # | 严重度 | 原文问题 | 修订 |
| --- | --- | --- | --- |
| 1 | **P1** | 审计留存期限写「至少 5 年」，引用 2022 版办法 | 现行为新《反洗钱法》第 34 条 + 11 号令第 44 条：**10 年**；2022 版从未生效且已废止。并补引擎 History Cleaning 默认 365 天清理的冲突与三层留存 |
| 2 | **P1** | 部署结构含 `forms/*.form` | Flowable 7.0 起移除 Form 引擎，Flowable 8 无表单消费方；改 `flowable:formKey` + 自建表单 |
| 3 | **P1** | 「一个 Business Rule Task 内部顺序调用多张 DMN」 | Flowable DMN 不支持 DRG/决策链；改为多 Decision Task 串联（或 decisionService），S3a/S3b 同步修正 |
| 4 | **P1** | UBO 记录/KYC 报告/客户信息表进流程变量 | 与 full 级历史全量明文留痕及敏感字段加密自相矛盾；变量只存轻量引用（对齐系统设计文档 5.2） |
| 5 | **P1** | `flowable:decisionTableKey` | 应为 `flowable:decisionTableReferenceKey` |
| 6 | P2 | 「共 5 个主 Stage」实列 6 项 | 更正为「5 个阶段、6 个子流程（S3 拆 a/b）」 |
| 7 | P2 | DMN 未定 hit policy | 清单 COLLECT、评级/处置/路由 FIRST + 兜底行（对齐系统设计文档 4.3.1） |
| 8 | P2 | DMN 与流程同 BAR 打包 | 独立部署独立版本化（改规则不发流程） |
| 9 | P2 | 会签三规则 vs 单一静态 completionCondition 不自洽 | DMN 输出 `passThreshold`/`vetoEnabled` 参数驱动；补提前结束后剩余待办作废留痕、计票并发提示、加签不可增实例数 |
| 10 | P2 | Link Event 弃用理由不准 | Flowable 中 Link 无执行语义，非「跨流程」问题 |
| 11 | P2 | Data Store 持久化 | 运行时语义弱；归档落 Service Task + 影像系统/业务库 |
| 12 | P3 | S3a/S3b「可并行」未落结构 | 明确 Parallel Gateway fork/join，S4 前汇聚 |
| 13 | P3 | 名单 4 类 | 对齐银反洗发〔2018〕19 号五类名单库口径（恐怖活动组织及人员名单） |

### 8.2 决策记录（2026-10-10 已定，正文已按结论回填）

| # | 决策点 | 结论 | 理由 |
| --- | --- | --- | --- |
| D1 | Flowable 版本 | **维持 8.0.x**，不升 8.1 | 仓库 Enforcer 锚定 `[7.0.0,8.0.0)`，ACT_* 表经 Flyway 治理；8.1 收益未确认，任何升级须人工核对 common/process/idm/eventregistry 四类 schema 差异补脚本 + 全量回归 |
| D2 | 阶段拆分 | **Call Activity**（S1–S5、sub-edd 独立子流程定义） | 巨型单定义的实例迁移脆弱性是长期成本；独立版本化 + 变更隔离（系统设计文档 4.1 口径）；变量经 `flowable:in`/`flowable:out` 显式映射 |
| D3 | 外部系统调用 | **统一异步 ServiceTask + 死信运维**，不使用 External Task | 与系统设计文档 6.3 一致，复用引擎线程与死信监控/人工重放基建；无非 JVM Worker 硬需求 |
| D4 | 《反洗钱特别预防措施管理办法》 | 处置语义即行引用；**文号与施行日期待核对正式文本后回填**（见 2.3） | 该件未见于系统设计文档表 2-1 监管清单（截至 2026-09）；处置语义与 19 号文/新《反洗钱法》一致，不悬置 |
| D5 | 客户类型枚举 | **同业剔除，独立流程定义** | 同业尽调口径与审批链路差异大，并入会破坏「一张图一类客户」纪律 |
| D6 | EDD 与 UBO 复核 | **补齐**：S3a 高风险分支挂 sub-edd（含高管会签）；S2 UBO 识别客户经理判断 + 合规复核双人留痕 | 11 号令第 28 条第五项高管批准是硬义务；12 号令禁止以系统化闭环替代人工判断 |

---

*文档整理日期：2026-10-10（同日回填 8.2 全部决策）。原文稿经对照《BPMN 2.0 / DMN 与 Flowable 8 完全指南》与《商业银行客户准入系统设计文档》评审修订；修订明细见 8.1，选型决策记录见 8.2。*
