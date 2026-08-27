# Claude Code Harness 机制参考

## 目录

0. 分类法（Scope × Category，报告矩阵的依据）
1. memory：CLAUDE.md 体系
2. agent：Subagents
3. instruction：.claude/rules/
4. skill：Skills
5. command：Slash commands
6. hook：正式 hooks 事件（30+ 种）、五种 hook 类型、async hooks、frontmatter hooks、output styles、MCP、plugins
7. setting：settings.json 体系
8. 加载顺序与优先级
9. 边界
附录：与 GitHub Copilot harness 的对照

## 0. 分类法（Scope × Category，七类）

| 类别 | Project（仓库级） | User（用户级） |
|---|---|---|
| **memory**<br>记忆文件 | CLAUDE.md（根/嵌套/@imports）、CLAUDE.local.md（已废弃）、AGENTS.md | ~/.claude/CLAUDE.md |
| **agent**<br>子代理 | .claude/agents/ | ~/.claude/agents/ |
| **instruction**<br>指令规则 | .claude/rules/ | ~/.claude/rules/（先于项目规则加载，项目优先） |
| **skill**<br>按需技能 | .claude/skills/ | ~/.claude/skills/ |
| **command**<br>slash 命令 | .claude/commands/（`/cmd` 手动触发） | ~/.claude/commands/ |
| **hook**<br>钩子/扩展 | settings 的 hooks 全部事件（30+ 种）、.claude/output-styles/、.mcp.json、skill/agent frontmatter hooks | user hooks、~/.claude/output-styles/、~/.claude.json mcpServers、~/.claude/plugins/ |
| **setting**<br>权限/约束 | .claude/settings.json（共享入库）、.claude/settings.local.json（个人不入库）：permissions/env/model/statusLine/disableAllHooks 等 | ~/.claude/settings.json：permissions/env/model |

command 与 hook 的区别：command 是**人手动触发**的 prompt 模板（`/cmd`）；hook 是**事件自动触发**的机制（hooks 全部事件、常驻扩展如 MCP）。

## 1. memory：CLAUDE.md 体系

- **项目 CLAUDE.md**：会话启动时自动注入；可放在仓库根或任意子目录（嵌套 CLAUDE.md 仅在访问该目录文件时按需加载）。
- **@imports**：`@path/to/file` 语法导入其他文件（支持 `~` 家目录、相对路径，最多嵌套 5 层）；常见模式 `@README.md`、`@docs/git-instructions.md`。
- **CLAUDE.local.md**：已废弃（deprecated），官方建议迁移到 @imports 或 settings.local.json。
- **AGENTS.md**：跨工具通用标准，Claude Code 同样识别。
- **用户记忆 ~/.claude/CLAUDE.md**：跨所有项目生效。
- 诊断：`/memory` 命令可查看当前加载的记忆文件；`#` 前缀快速追加记忆。
- 健康度：CLAUDE.md 建议 < 300 行，内容应是"非显而易见"的项目约定，不要重复代码自明的信息。

## 2. agent：Subagents

- 路径：`.claude/agents/*.md`（项目）、`~/.claude/agents/`（用户）。
- 独立上下文的专职代理；frontmatter：`name`、`description`（决定何时自动委派）、
  `tools`（白名单，省略=全部）、`model`。
- 正文为空（只有 frontmatter）视为配置缺陷（形同虚设）。

## 3. instruction：.claude/rules/

- 模块化规则目录，多个 .md 文件按主题拆分，全部自动注入（比单个超长 CLAUDE.md 更易维护）。
- frontmatter 可用 `paths:` 做路径作用域（支持 `{ts,tsx}` brace 展开）。
- **用户级 ~/.claude/rules/**：跨所有项目生效；先于项目规则加载（项目规则优先）；
  支持 symlink 共享规则集（循环链接被检测处理）；
  已知 bug：`paths:` 规则只匹配启动时 CWD 下的文件，`--add-dir` 不扩展匹配范围。

## 4. skill：Skills

- 路径：`.claude/skills/<name>/SKILL.md`（项目）、`~/.claude/skills/`（用户）。
- 按需加载的能力包；`description` 是触发面。
- frontmatter 支持字段：`name`（必须，与目录名一致）、`description`（必须，≤1024 字符）、
  `allowed-tools`（可选，限制 skill 激活时可用的工具）、`license`、`compatibility`、`metadata`。
- **渐进式披露**：启动时仅加载所有 skill 的 name+description（~100 tokens/skill）；
  skill 被激活时加载完整 SKILL.md 正文；references/scripts/ 等辅助文件仅在需要时读取。
  建议正文 < 500 行，超长内容拆入 `references/` 子目录。
- skill 和 agent 的 frontmatter 中可直接定义 hooks（见 §6.5），仅在组件激活时生效。
- 正文为空视为配置缺陷。

## 5. command：Slash commands

- 路径：`.claude/commands/*.md`（项目）、`~/.claude/commands/`（用户）。
- **手动触发**：`/文件名`；支持子目录命名空间。
- frontmatter：`description`、`argument-hint`、`allowed-tools`、`model`；正文支持 `$ARGUMENTS` 占位。

## 6. hook：Hooks 事件、类型、async、frontmatter hooks、output styles、MCP、plugins

### 6.1 Hooks 总览

Hooks 是用户定义的 shell 命令、HTTP 端点、MCP 工具调用、LLM prompt 或子 agent，在 Claude Code 生命周期的特定点自动执行。配置在 settings 的 `hooks` 键下。

**五种 hook 类型：**

| 类型 | 说明 |
|---|---|
| `command` | 执行 shell 命令，stdin 接收 JSON，exit code + stdout 返回结果。支持 exec form（`args` 数组）和 shell form（`command` 字符串） |
| `http` | 发送 HTTP POST 到指定 URL，响应体返回 JSON 结果 |
| `mcp_tool` | 调用已连接 MCP server 的工具，工具输出文本按 command hook stdout 规则处理 |
| `prompt` | 发送 prompt 到 Claude 模型做单轮评估（默认 Haiku），返回 `{ok: true/false}` 决策 |
| `agent` | 生成子 agent，可使用 Read/Grep/Glob 等工具验证条件后返回决策（实验性，最多 50 轮） |

**各事件支持的 hook 类型：**

- 支持**全部五种**类型的事件：`PreToolUse`、`PostToolUse`、`PostToolUseFailure`、`PostToolBatch`、
  `PermissionRequest`、`PermissionDenied`、`UserPromptSubmit`、`UserPromptExpansion`、`Stop`、`SubagentStop`、
  `TaskCreated`、`TaskCompleted`、`TeammateIdle`
- 支持 **command / http / mcp_tool** 的事件：`ConfigChange`、`CwdChanged`、`DirectoryAdded`、`Elicitation`、
  `ElicitationResult`、`FileChanged`、`InstructionsLoaded`、`Notification`、`PostCompact`、`PreCompact`、
  `SessionEnd`、`StopFailure`、`SubagentStart`、`WorktreeCreate`、`WorktreeRemove`
- 支持 **command / mcp_tool** 的事件：`SessionStart`、`Setup`

### 6.2 Hook 事件（按生命周期分组，共 31 种）

| 分组 | 事件 | 时机 | 典型用途 |
|---|---|---|---|
| **会话生命周期** | `SessionStart` | 会话开始/恢复（matcher: startup/resume/fork/clear） | 加载环境、注入状态、持久化 env |
| | `Setup` | `--init-only`/`--init`/`--maintenance` + `-p` 时（matcher: init/maintenance） | 一次性依赖安装、定时清理 |
| | `SessionEnd` | 会话结束（matcher: normal/clear/exit/...） | 清理、日志 |
| **指令加载** | `InstructionsLoaded` | CLAUDE.md / rules 文件加载时（matcher: load_reason） | 审计、合规追踪 |
| **用户输入** | `UserPromptSubmit` | 用户提交 prompt 时 | 注入上下文、校验、阻止 |
| | `UserPromptExpansion` | slash command 展开时（matcher: command_name） | 阻止特定命令、注入上下文 |
| **显示** | `MessageDisplay` | assistant 消息流式渲染时 | 去 markdown、脱敏 |
| | `Notification` | 通知时（matcher: notification_type） | 接入外部提醒 |
| **工具执行** | `PreToolUse` | 工具执行前，可阻断/修改（matcher: tool_name） | 强制格式化、拦截危险命令、修改参数 |
| | `PermissionRequest` | 即将请求权限时（matcher: tool_name） | 代替用户批准/拒绝 |
| | `PostToolUse` | 工具执行成功后（matcher: tool_name） | 自动 lint/test、替换输出 |
| | `PostToolUseFailure` | 工具执行失败后（matcher: tool_name） | 日志、告警、纠正反馈 |
| | `PostToolBatch` | 一批工具调用全部完成后（无 matcher） | 批量后处理 |
| | `PermissionDenied` | auto mode 拒绝工具时（matcher: tool_name） | 日志、通知重试 |
| **Agent 生命周期** | `SubagentStart` | 子 agent 启动时（matcher: agent_type） | 注入上下文到子 agent |
| | `SubagentStop` | 子 agent 结束时（matcher: agent_type） | 子任务验证 |
| | `Stop` | 主 agent 结束时（无 matcher） | 收尾检查 |
| | `StopFailure` | API 错误导致结束时（matcher: error type） | 告警、恢复 |
| | `TeammateIdle` | teammate 即将空闲时（无 matcher） | 质量门禁 |
| **任务管理** | `TaskCreated` | 任务创建时（无 matcher） | 命名规范、阻止创建 |
| | `TaskCompleted` | 任务标记完成时（无 matcher） | 完成条件检查 |
| **配置变更** | `ConfigChange` | 配置文件变更时（matcher: source） | 审计、安全策略 |
| | `CwdChanged` | 工作目录变更时（无 matcher） | 重新加载环境 |
| | `DirectoryAdded` | 工作目录添加时（matcher: source） | 准备新仓库 |
| | `FileChanged` | 受监视文件变更时（matcher: file basename） | 重载环境变量 |
| **上下文压缩** | `PreCompact` | 压缩前（matcher: manual/auto） | 备份关键状态 |
| | `PostCompact` | 压缩后（matcher: manual/auto） | 更新外部状态 |
| **Worktree** | `WorktreeCreate` | worktree 创建时（无 matcher） | 非 git VCS 支持 |
| | `WorktreeRemove` | worktree 移除时（无 matcher） | 清理 |
| **MCP** | `Elicitation` | MCP server 请求用户输入时（matcher: server name） | 程序化响应 |
| | `ElicitationResult` | 用户响应后（matcher: server name） | 观察/修改/阻止响应 |

### 6.3 Hook 执行机制

- **matcher**：按事件匹配不同字段（tool_name、notification_type、agent_type、source 等）；
  支持正则匹配与精确匹配（含 `|` 或 `,` 分隔的备选列表）。
- **`if` 字段**：单个 hook handler 可用 permission rule 语法（如 `"Bash(git *)"`、`"Edit(*.ts)"`）进一步过滤。
- **exit code**：0 = 成功（stdout 可能含 JSON 决策）；2 = 阻断（stderr 反馈给模型）；其他 = 非阻断错误。
- **JSON output**：通过 stdout 输出 JSON 精细控制行为——`decision`（block）、`hookSpecificOutput`
  （permissionDecision: allow/deny/ask/defer、updatedInput、additionalContext 等）、`continue`（false 停止）、
  `systemMessage`（面向用户）、`terminalSequence`（终端通知转义序列）。
- **路径占位符**：`${CLAUDE_PROJECT_DIR}`、`${CLAUDE_PLUGIN_ROOT}`、`${CLAUDE_PLUGIN_DATA}`。
- 诊断：`/hooks` 命令查看已注册 hooks（只读浏览器，显示五种类型与来源标签）。

### 6.4 Async Hooks

- **`async: true`**（仅 `type: "command"`）：hook 在后台执行，不阻塞 Claude；
  输出（`additionalContext`、`systemMessage`）在下一轮对话交付。
- async hooks **不能阻断**或控制行为——触发动作已完成。
- 适用于：部署、测试套件、外部 API 调用等长时间运行任务。
- 非交互模式（`-p`）下 teardown 会终止未完成的 async hooks。

### 6.5 Frontmatter Hooks（skill / agent 内嵌）

- skill 和 agent 的 YAML frontmatter 中可直接定义 hooks，格式与 settings hooks 相同。
- **仅在组件激活时生效**，组件结束后自动清理。
- 所有 hook 事件均支持；subagent 的 `Stop` hooks 自动转换为 `SubagentStop`。
- 安全要求：项目 subagent 的 frontmatter hooks 需先接受 workspace trust dialog。

### 6.6 Hook 来源与作用域

| 来源 | 作用域 | 说明 |
|---|---|---|
| User Settings | 全局 | `~/.claude/settings.json` |
| Project Settings | 项目 | `.claude/settings.json` |
| Local Settings | 项目（个人） | `.claude/settings.local.json` |
| Managed Policy | 企业（最高优先级） | managed-settings.json |
| Plugin Hooks | 插件 | plugin 的 `hooks/hooks.json` |
| Session Hooks | 当前会话 | 内存注册 |
| Built-in Hooks | 内置 | Claude Code 内部注册 |
| Frontmatter Hooks | 组件生命周期 | skill / agent YAML frontmatter |

Hooks 跨层级**合并**而非替换。`disableAllHooks` 可禁用非 managed hooks。

### 6.7 Hook 安全配置

| 设置键 | 说明 |
|---|---|
| `disableAllHooks` | 全局禁用所有 hooks（managed hooks 需在 managed settings 中设置才可禁用） |
| `allowedHttpHookUrls` | HTTP hook URL 白名单（在任意 settings 层级定义即生效，跨所有来源） |
| `httpHookAllowedEnvVars` | HTTP hook header 环境变量插值白名单 |
| `allowManagedHooksOnly` | 企业管理员使用，阻止 user/project/plugin hooks |

### 6.8 Output styles

- 路径：`.claude/output-styles/*.md`（项目）、`~/.claude/output-styles/`（用户）。
- 改变系统提示风格（如 Explanatory、Learning）。

### 6.9 MCP

- 项目级 `.mcp.json`（mcpServers 键）；用户级在 `~/.claude.json` 顶层 `mcpServers`。
- `.mcp.json` 的 server 默认需用户批准，可用 `enabledMcpjsonServers` 预批准。

### 6.10 Plugins

- `~/.claude/plugins/`：可打包 commands/agents/skills/hooks 分发。

## 7. setting：settings.json 体系

三个层级：
- `.claude/settings.json`：团队共享，入库
- `.claude/settings.local.json`：个人，应 gitignore
- `~/.claude/settings.json`：用户级

关键键：

| 键 | 说明 |
|---|---|
| `permissions.allow` | 免确认放行的工具规则，如 `Bash(npm run test:*)`、`Read(./src/**)` |
| `permissions.deny` | 硬性禁止，如 `Bash(rm -rf*)`、`Read(./.env*)`、`WebFetch` |
| `permissions.ask` | 强制每次询问 |
| `permissions.defaultMode` | `default` / `acceptEdits` / `plan` / `bypassPermissions`（高危，仅限容器） |
| `permissions.additionalDirectories` | 允许访问工作区外的目录 |
| `env` | 注入到每个会话的环境变量（含 `ANTHROPIC_*`、`CLAUDE_CODE_*` 开关） |
| `model` | 锁定模型 |
| `statusLine` | 自定义状态栏命令 |
| `includeCoAuthoredBy` | 提交署名开关 |
| `enabledMcpjsonServers` / `disabledMcpjsonServers` | 显式启用/禁用 .mcp.json 中的 server |
| `enableAllProjectMcpServers` | 全部放行（有供应链风险） |
| `disableAllHooks` | 全局禁用 hooks（managed hooks 除外） |
| `allowedHttpHookUrls` | HTTP hook URL 白名单 |
| `httpHookAllowedEnvVars` | HTTP hook 环境变量插值白名单 |
| `allowManagedHooksOnly` | 仅允许 managed hooks（企业管理） |
| `cleanupPeriodDays` | 会话记录保留天数 |

规则语法：`Tool(规则)`，如 `Bash(git push:*)`；`*` 为通配符。deny 优先于 ask 优先于 allow。

## 8. 加载顺序与优先级

settings 优先级（高 → 低）：
1. Enterprise managed settings（macOS `/Library/Application Support/ClaudeCode/managed-settings.json`、
   Linux/WSL `/etc/claude-code/managed-settings.json`、Windows `C:\ProgramData\ClaudeCode\managed-settings.json`）
2. 命令行参数
3. `.claude/settings.local.json`
4. `.claude/settings.json`
5. `~/.claude/settings.json`

deny 规则跨层级合并后仍优先于 allow；rules 加载顺序：用户级 → 项目级（项目优先）。
hooks 跨层级合并而非替换；`disableAllHooks` 无法禁用 managed hooks。

## 9. 边界

- Enterprise managed settings：脚本探测三个平台路径的存在性并在矩阵脚注报告，不展开内容；
- `~/.claude.json` 含会话历史等大量状态，脚本只提取 `mcpServers` 键，不输出其余内容；
- env 中含 key/token/secret/pass 字样的值会被脱敏为 `***`。

## 附录：与 GitHub Copilot harness 的对照

| 类别 | Claude Code | GitHub Copilot |
|---|---|---|
| memory | CLAUDE.md（+@imports）、AGENTS.md | AGENTS.md / CLAUDE.md / GEMINI.md |
| agent | .claude/agents/、~/.claude/agents/ | .github/agents/、~/.copilot/agents/ |
| instruction | .claude/rules/、~/.claude/rules/ | copilot-instructions.md、*.instructions.md、~/.copilot 个人指令 |
| skill | .claude/skills/、~/.claude/skills/ | .github/skills、.agents/skills、~/.copilot/skills 等 |
| command | .claude/commands/、~/.claude/commands/ | .github/prompts/*.prompt.md |
| hook | hooks 30+ 事件、五种类型（command/http/mcp_tool/prompt/agent）、async hooks、frontmatter hooks、output styles、plugins | CLI 正式 hooks（.github/hooks/ 等）；VS Code 仅近似机制 |
| setting | settings.json 三层 permissions/env/model/disableAllHooks 等 | .vscode/settings.json 的 chat.* 键、~/.copilot/settings.json |
| MCP | .mcp.json + ~/.claude.json | .vscode/mcp.json、.github/mcp.json、mcp-config.json |

## 官方来源

- Claude Code settings — https://code.claude.com/docs/en/settings
- Claude Code memory — https://code.claude.com/docs/en/memory
- Claude Code hooks（完整事件参考）— https://code.claude.com/docs/en/hooks
- Claude Code hooks guide — https://code.claude.com/docs/en/hooks-guide
- Claude Code skills — https://code.claude.com/docs/en/skills
- Claude Code subagents — https://code.claude.com/docs/en/sub-agents
- Claude Code slash commands — https://code.claude.com/docs/en/slash-commands
- Claude Code scheduled tasks（/loop & cron）— https://code.claude.com/docs/en/scheduled-tasks
- Skill 编写最佳实践 — https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices
- Agent Skills 规范 — https://agentskills.io/specification

## 附录 C：Loop Engineering 机制

Loop Engineering 指控制和优化 AI Agent 在其 agentic loop（推理→行动→观察→重复）中行为的工程实践。

### C.1 循环调度：/loop skill 与 cron 工具

- **`/loop` skill**（内置）：在固定间隔或自适应间隔重复执行 prompt。
  - 固定间隔：`/loop 5m /foo`（支持 s/m/h/d 单位）
  - 自适应间隔：`/loop /foo`（Claude 根据观察动态选择 1min–1h 延迟）
  - 内置 maintenance prompt：裸 `/loop` 执行未完成工作、PR 维护、清理
- **cron 工具**：`CronCreate`/`CronDelete`/`CronList`，标准 5 字段 cron 表达式。
  - 会话级：最多 50 个任务，7 天后自动过期。
  - jitter：recurring 最多延迟 30 分钟（或间隔的一半）。
- **`ScheduleWakeup` 工具**：自适应模式下 Claude 自行决定何时（或是否）继续。
- **`Monitor` 工具**：后台脚本监控，流式回传输出（比轮询更高效）。
- **`loop.md` 自定义**：`.claude/loop.md`（优先）或 `~/.claude/loop.md`，替换内置 maintenance prompt。≤25KB。
- **`CLAUDE_CODE_DISABLE_CRON=1`**：禁用调度器，`/loop` 和 cron 工具不可用。
- Stop hook 输入包含 `session_crons` 和 `background_tasks` 数组（v2.1.145+）。

### C.2 迭代控制：effort 与超时

| 设置 | 默认 | 影响 |
|---|---|---|
| `effort` | medium | extended thinking 预算（low/medium/high/max），max 约为 medium 的 20× 成本 |
| `BASH_MAX_TIMEOUT_MS`（env） | — | Bash 命令最大超时（毫秒） |
| `BASH_DEFAULT_TIMEOUT_MS`（env） | — | Bash 命令默认超时（毫秒） |
| hook `timeoutSec` | 600（sync）/ 600（async） | hook 执行超时（秒） |
| SessionEnd hook timeout | 1.5s | 会话结束 hook 超时 |

### C.3 循环自治：permissions 与 sandbox

| 设置 | 默认 | 影响 |
|---|---|---|
| `permissions.defaultMode` | default | `default`（每步确认）/ `acceptEdits`（自动接受编辑）/ `plan`（计划模式）/ `bypassPermissions`（全自动，高风险） |
| `sandbox.enabled` | false | sandbox 隔离 Bash 命令的文件系统和网络访问 |
| `sandbox.filesystem.allowWrite/denyWrite/denyRead/allowRead` | — | 细粒度文件系统路径控制 |
| `sandbox.network.allowedDomains` | — | 允许访问的网络域 |
| `disableAllHooks` | false | 禁用所有非 managed hooks（影响循环中的 hook 拦截） |

### C.4 上下文管理

- **autoCompact**：上下文窗口接近满时自动触发 compaction。
- **PreCompact hook**：compaction 前触发（matcher: manual/auto），可 block。
- **PostCompact hook**：compaction 后触发，可更新外部状态。
- Stop hook 的 `stop_hook_active` 字段标记当前 turn 是否已被强制继续。

### C.5 停止条件与 runaway guard

- **Stop hook**：主 agent 结束时触发，可 `decision: "block"` + `reason` 强制继续。
- **SubagentStop hook**：子 agent 结束时触发，同样可 block。
- **runaway guard**：连续 8 次 `block` 后 CLI 自动放行，防止无限循环。
- **StopFailure hook**：API 错误导致结束时触发（非正常 Stop）。

### C.6 后台执行

- **async hooks**（`async: true`）：hook 在后台执行，不阻塞 Claude；输出在下一轮对话交付。
- **`run_in_background`**：Bash 工具的后台执行模式。
- **Monitor 工具**：后台脚本监控 + 流式输出回传。
- 后台任务和 session crons 在 Stop hook 输入中报告，用于区分"会话结束"与"会话等待后台任务"。
