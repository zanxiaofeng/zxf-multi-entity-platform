---
name: harness-inspector
description: >
  盘点并报告当前项目在 Claude Code 上的 harness setting（项目级与用户级配置），
  以「Scope（User/Project）× Category（memory/agent/instruction/skill/command/hook/setting 七类）」矩阵表输出。
  覆盖：CLAUDE.md（含嵌套与 @imports）、.claude/rules/、settings.json 与 settings.local.json
  （permissions/hooks/env/model/statusline）、hooks 全部事件（30+ 种，含 command/http/mcp_tool/prompt/agent
  五种 hook 类型，async hooks，以及 skill/agent frontmatter 内嵌 hooks）、.claude/commands/、.claude/agents/、
  .claude/skills/、.claude/output-styles/、.mcp.json，以及用户级 ~/.claude/* 与 ~/.claude.json。
  当用户询问"当前项目的 Claude Code 配置/记忆/权限/hooks"、"排查 Claude Code 没有遵循项目规范"、
  "审计 .claude 目录"、"对比 Claude Code 与 GitHub Copilot 的 harness 机制"时使用本 skill。
allowed-tools: Read, Grep, Glob, Bash
---

# Claude Code Harness Inspector

## 目标

对指定项目做完整的 Claude Code harness 盘点，输出**矩阵优先**的结构化中文 Markdown 报告。

## 执行步骤

1. 运行扫描脚本（确定性操作，不要手工逐个 find）：

   ```bash
   python3 <skill_dir>/scripts/scan_claude_harness.py <项目根目录> --md --user-level
   ```

   不需要用户级扫描时去掉 `--user-level`；需要原始数据时省略 `--md` 输出 JSON。

2. 对照 `references/claude-harness-mechanisms.md` 逐项解读：
   - 每个机制的作用、加载顺序、默认值；
   - 配置健康度诊断（见下）。

3. 在报告末尾补充**诊断与建议**章节，常见检查点：
   - `settings.json`（入库共享）与 `settings.local.json`（个人不入库）职责是否混淆——
     团队共享的 permissions 应进 `settings.json`，个人偏好进 `settings.local.json`；
   - deny 规则是否覆盖了危险命令（`Bash(rm -rf*)`、`Read(./.env*)` 等）；
   - hooks 的 matcher 是否过宽（`*` 匹配所有工具会显著拖慢会话）；
   - hook 是否使用了高级类型（`http`、`prompt`、`agent`、`mcp_tool`），确认其安全配置；
   - `disableAllHooks` 是否被误设为 `true`（所有 hooks 静默失效，且无法禁用 managed hooks）；
   - `allowedHttpHookUrls` / `httpHookAllowedEnvVars` 是否合理配置（HTTP hook 安全）；
   - CLAUDE.md 是否过长（> 几百行会稀释上下文）；@import 引用的文件是否存在；
   - `.claude/agents` / `.claude/skills` 正文为空（形同虚设）；
   - agent/skill 的 frontmatter 中是否内嵌了 hooks（frontmatter hooks 仅在组件激活时生效，容易被忽略）；
   - `permissions.defaultMode` 是否设为 `bypassPermissions`（高风险，仅容器环境可接受）；
   - `.mcp.json` 的 server 是否需要 `enabledMcpjsonServers` 显式启用。
   - **Loop Engineering**（参照报告 §9 与 `references` 附录 C）：
     `effort` 是否设为 `max`（高成本，约 20× medium）；`permissions.defaultMode` 是否为 `bypassPermissions`（循环全自动）；
     `sandbox` 是否启用（未启用时 agent 无限制访问文件系统/网络）；`CLAUDE_CODE_DISABLE_CRON` 是否为 `1`（`/loop` 不可用）；
     `loop.md` 是否存在（自定义循环行为）；`BASH_MAX_TIMEOUT_MS` 是否设置（未设时 Bash 无超时上限）。

## 分类法（矩阵依据，七类）

- **memory**：CLAUDE.md（项目/嵌套/用户，含 @imports）——自动注入的记忆文件
- **agent**：.claude/agents/、~/.claude/agents/——子代理
- **instruction**：.claude/rules/、~/.claude/rules/——指令规则文件（用户级先加载，项目级优先）
- **skill**：.claude/skills/、~/.claude/skills/——按需加载技能
- **command**：.claude/commands/、~/.claude/commands/——手动触发的 slash commands（`/cmd`）
- **hook**：settings 的 hooks 全部事件（30+ 种）、五种 hook 类型（command/http/mcp_tool/prompt/agent）、
  async hooks、skill/agent frontmatter hooks、output styles、MCP servers——触发与扩展
- **setting**：settings.json 的 permissions（allow/deny/ask/defaultMode）、env、model、statusLine、
  disableAllHooks、HTTP hook 安全配置——行为约束与权限

优先级（高 → 低）：enterprise managed settings > 命令行参数 > `settings.local.json` >
`settings.json`（项目） > `~/.claude/settings.json`（用户）。

## 边界（必须向用户说明）

- Enterprise managed settings 跨平台路径：macOS `/Library/Application Support/ClaudeCode/managed-settings.json`、
  Linux/WSL `/etc/claude-code/managed-settings.json`、Windows `C:\ProgramData\ClaudeCode\managed-settings.json`；
  脚本会探测三个路径的存在性并在矩阵脚注中报告，但不展开其内容（需管理员权限读取时以存在性为准）；
- `~/.claude.json` 含会话历史等大量状态，脚本只提取 `mcpServers` 键，不输出其余内容；
- env 中含 key/token/secret/pass 字样的值会被脱敏为 `***`。

## 输出要求

- 中文 Markdown，**开篇必须是 Harness 分类矩阵**（脚本章节 0）；
  矩阵之后的明细章节**按 category 顺序排列**（1 memory → 2 agent → 3 instruction → 4 skill →
  5 command → 6 hook → 7 setting → 8 用户级明细），与矩阵七行一一对应。
- 只报告元信息与诊断结论；不贴 CLAUDE.md / 指令文件全文，除非用户要求。
