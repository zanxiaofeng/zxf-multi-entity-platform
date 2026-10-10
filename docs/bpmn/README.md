# 对公客户准入流程 BPMN 交付件

《[商业银行对公客户准入流程-BPMN建模与Flowable实现方案](../商业银行对公客户准入流程-BPMN建模与Flowable实现方案.md)》第六章部署结构对应的 BPMN 2.0 XML（12 个流程定义 + `dmn/` 10 张决策表）。**设计交付物，不在构建路径**——落地到实体模块 `resources/processes/` 前见下文「落地前置条件」。

## 文件清单

| 文件 | process key | 对应文档章节 | 说明 |
| --- | --- | --- | --- |
| `onboarding-corp.bpmn` | `onboarding-corp` | 一、二章 | 主流程：双启动（None + Message）→ S1→S2→(S3a∥S3b)→分流→S4→S5 / P1 / P3 |
| `sub-s1-precheck.bpmn` | `sub-s1-precheck` | 2.1 | 受理与资料预检（DMN `document-checklist`，补件回退在子流程内部） |
| `sub-s2-kyc.bpmn` | `sub-s2-kyc` | 2.2 | 尽调与 KYC（UBO 三类标准 + 合规复核双人留痕 + 备案核对） |
| `sub-s3a-screening.bpmn` | `sub-s3a-screening` | 2.3 | 筛查与风险评级（DMN `screening-disposition`/`risk-rating` 串联，高风险→sub-edd） |
| `sub-s3b-eligibility.bpmn` | `sub-s3b-eligibility` | 2.4 | 准入规则校验（六张 DMN = 六个 Business Rule Task 串联——Flowable 无 DRG） |
| `sub-s4-approval.bpmn` | `sub-s4-approval` | 2.5 | 审批决策（DMN 参数驱动会签 completionCondition + 超时升级） |
| `sub-s5-account.bpmn` | `sub-s5-account` | 2.6 | 签约开户归档（Event-Based Gateway：回执/超时先到先走） |
| `sub-edd.bpmn` | `sub-edd` | 2.3 | 强化尽调 + 高级管理层批准（多实例会签，一票否决） |
| `sub-conditions-fulfillment.bpmn` | `sub-conditions-fulfillment` | 2.5 | 条件落实（有条件通过）：登记→落实+周期催办→复核→放行/回环/超期决断（宽限/撤销），`conditionsMet` 回传 S4 分流 |
| `reject-appeal.bpmn` | `reject-appeal` | 3.1 | P1 拒绝与申诉（申诉窗口事件网关；成功发起新实例，8.3-A1） |
| `lifecycle.bpmn` | `lifecycle` | 3.2 | P2 存续期管理（周期重检/交易监控命中/客户退出三出口事件网关） |
| `aml-report.bpmn` | `aml-report` | 3.3 | P3 AML 合规上报（保密，含上报结果回写 8.3-A2） |

## DMN 决策表（`dmn/`，decision id = BRT `decisionTableReferenceKey`）

| 文件 | decision id | hit policy | 输入变量来源 | 输出变量 |
| --- | --- | --- | --- | --- |
| `document-checklist.dmn` | `document-checklist` | **COLLECT**（清单型天然完备，无兜底行） | S1 发起表单（customerType/businessType） | `requiredDocument` 多命中数组（存入决策 key 命名变量） |
| `screening-disposition.dmn` | `screening-disposition` | FIRST（最严置顶+兜底） | `svcScreening` 写入 `hitFlag` | `disposition`、`amlReportRequired` |
| `risk-rating.dmn` | `risk-rating` | FIRST（最严置顶+兜底） | `svcScreening` 写入评分；S2 传入 `structureComplex` | `riskLevel` |
| `industry-policy.dmn` | `industry-policy` | FIRST+兜底 | `svcAssembleInput` 预置 `industryCode` | `industryPassed`、`industryNote` |
| `region-policy.dmn` | `region-policy` | FIRST+兜底 | `svcAssembleInput` 预置 `regionCode`/`isCrossBorder` | `regionPassed`、`regionNote` |
| `limit-eligibility.dmn` | `limit-eligibility` | FIRST+兜底 | S1 采集 `applyAmount` | `limitPassed`、`suggestedLimit` |
| `product-fit.dmn` | `product-fit` | FIRST+兜底 | `svcAssembleInput` 预置 `productCode` | `productFit`、`productNote` |
| `blacklist-check.dmn` | `blacklist-check` | FIRST+兜底 | `svcAssembleInput` 名单库比对预置 `blacklistHit` | `blacklistPassed`、`blacklistAction` |
| `exception-trigger.dmn` | `exception-trigger` | FIRST+兜底 | 前五张表输出（汇聚节点） | `eligibilityPassed`、`exceptionRequired`、`exceptionReason` |
| `approval-routing.dmn` | `approval-routing` | FIRST（最严置顶+兜底） | 流程变量 + `svcAssembleApprovalInput` 预置 `isGroupCustomer`/`isCrossBorder` | `approvalLevel`、`approverGroup`、`signRule` |

规则行均为**示意骨架**，由对应业务条线按行内制度补全维护（表内注释已标注归属）；阈值/清单类数值以 `SUGGESTED` 待审。COLLECT 聚合结果返回 Double 的版本陷阱（完全指南 19.3）不适用本批——仅 document-checklist 用 COLLECT 且无聚合符。

## 设计决策映射（文档 8.1/8.2/8.3）

- **D2**：S1–S5、sub-edd 均为独立流程定义，主流程以 Call Activity 编排，变量经 `flowable:in`/`flowable:out` 显式映射；
- **D3**：外部系统调用全部异步 ServiceTask（`flowable:async="true"` + `R3/PT10M` + 死信），无 External Task；
- **多表 DMN**：单 Business Rule Task 只执行一张表（无 DRG），S3a 两张、S3b 六张拆节点串联；
- **会签规则参数化**：completionCondition 只消费 `vetoEnabled`/`passThresholdCount` 变量，阈值换算在 `approvalParamDelegate`；
- **SLA 配置化**：`approvalSla`/`accountReceiptTimeout`/`appealWindow`/`recheckInterval` 均为流程变量注入，不写死在流程定义。

## 使用注意

1. **无 DI 图形坐标**：文件只含模型层（对齐仓库 `order-approval.bpmn20.xml` 风格），引擎可直接部署执行；导入 Flowable Modeler / bpmn.io 后需自动布局。后缀 `.bpmn` 与 `.bpmn20.xml` 均为引擎与 Spring Boot starter 自动部署默认支持——已从 flowable-spring-boot-autoconfigure 8.0.0 验证，`classpath*:/processes/` 默认同时匹配 `**.bpmn20.xml` 与 `**.bpmn`，落地无需额外配置。
2. **DMN 部署目录陷阱**：10 张决策表已随批交付（`dmn/`），落地时放 `src/main/resources/dmn/`（**不是** `processes/`），后缀 `.dmn` 或 `.dmn.xml`——放错目录 Business Rule Task 执行时报 `No decision found for key: xxx`（完全指南 21.1）。DMN 独立部署、独立版本化：改规则不发流程（文档四-1）。生产配置 `flowable.dmn.history-enabled: true`（决策执行输入输出可追溯）。
3. **引用的 delegate bean 未实现**（`docs/bpmn` 不进构建，无影响）：`identityVerificationDelegate`、`uboFilingCheckDelegate`、`sanctionScreeningDelegate`、`lifecycleScreeningDelegate`、`approvalParamDelegate`、`approvalResultListener`（taskListener）、`approvalService`/`eddApprovalService`（collection 表达式）、`customerNotifyDelegate`、`accountOpenDelegate`、`accountArchiveDelegate`、`accountReceiptTimeoutDelegate`、`appealRestartDelegate`、`reOnboardingDelegate`、`amlReportDelegate`、`amlResultRecordDelegate`、`documentsCheckDelegate`（S1 资料齐全比对）、`s3bInputAssemblyDelegate`（S3b 校验输入装配）、`approvalInputAssemblyDelegate`（S4 审批输入装配）、`conditionsRegistrationDelegate`/`conditionsUrgeDelegate`/`conditionsNotifyDelegate`/`conditionsExtendDelegate`/`conditionsRevokeDelegate`（条件落实子流程）。
4. **`sub-conditions-fulfillment` 已补齐**（原登记 gap 已闭环）：S4 有条件通过分支经 `gwConditionsOutcome` 消费其 `conditionsMet` 输出——全部落实→通过，撤销批准→按拒绝处理；子流程内不留无限挂起路径（超期必经决断，与申诉上限同一完整性纪律）。
5. **消息清单**（message name，投递方按名 + correlation 变量定位）：`onboarding-request`（主流程 Message Start）、`account-open-result`（S5 回执）、`customer-appeal`（P1）、`trade-monitoring-hit`、`customer-exit`（P2）。

## 落地前置条件（进实体模块 `resources/processes/`）

- delegate 实现须**继承 `EntityContextAwareDelegate`**（实体模块 ArchUnit 强制，Job 线程从流程变量重建上下文）、专属 delegate 以 `@ForEntity` 限定；
- 通用 delegate（通知/归档类）进 platform-core，专属（名单筛查等）进实体模块；
- ACT_* 表已由 `common/V3` Flyway 脚本治理，部署即用；**装配冒烟测试**须扩展断言：本批流程同 key 唯一、callActivity 引用的子流程 key 已部署、delegate 全装配（引擎不做启动期校验，README Review 硬性规则 5）；
- 两实体拓扑差异时按「同 key 不同拓扑」惯例拆至各自实体模块（文档第七章）。
