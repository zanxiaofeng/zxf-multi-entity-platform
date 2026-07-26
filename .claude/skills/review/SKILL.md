---
name: review
description: 全量代码评审工作流——先通读 .claude/rules 适用规范全文，再通读全部目标代码，按严重度分级编号输出发现；默认只评审不改代码，修复后双装配验证
---

# Review 工作流

严格按顺序执行，不得跳步：

1. **读规范**：按 CLAUDE.md「.claude/rules 规范库」索引，通读与本次任务匹配的全部规范文件**全文**
   （Java 代码至少 `java-coding-standard.md` + `code-review.md`；含测试代码再加 `test-conventions.md`；
   涉及 SQL/yaml/依赖/POM 时按索引补 `db-*`、`tech-stack.md` 等）。禁止仅凭规范标题或记忆评审。
2. **读代码**：通读任务范围内全部文件——主代码、测试、yaml/BPMN/SQL/POM/CI 配置。
   禁止只读 diff、禁止抽样。
3. **输出发现**：按 高/中/低 严重度分级、连续编号；每条给出 `file:line`、问题描述、
   规范出处（哪个文件哪条规则）、修复建议。同时明确列出"已逐条核对且符合"的规范项
   （防止漏核）；不臆造不存在的问题，拿不准的标注为待确认。
4. **等待指令**：默认不修改任何代码。用户指定编号或级别后才动手修复。
5. **修复后验证**：必须 `mvn -B verify -Palpha` 与 `mvn -B verify -Pbeta` 双装配全绿
   （app 测试按 `assembly.entity` 门控，对侧实体的 app 测试跳过属预期），
   并在总结中给出各模块测试数变化。
