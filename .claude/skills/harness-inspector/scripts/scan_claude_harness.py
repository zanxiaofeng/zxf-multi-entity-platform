#!/usr/bin/env python3
"""Scan a project directory for Claude Code harness settings.

Detects every mechanism that shapes Claude Code's behavior, classified into
scope(User/Project) x category(memory/agent/instruction/skill/command/hook/setting):
- memory: CLAUDE.md (project/nested/imports), ~/.claude/CLAUDE.md
- instruction: .claude/rules/ (project) and ~/.claude/rules/ (user-level, loaded first, project wins)
- setting: .claude/settings.json, .claude/settings.local.json, ~/.claude/settings.json
  (permissions/env/model/statusline/disableAllHooks/HTTP hook security)
- hook: settings.json "hooks" (31 lifecycle events: SessionStart/Setup/InstructionsLoaded/
  UserPromptSubmit/UserPromptExpansion/MessageDisplay/PreToolUse/PermissionRequest/PostToolUse/
  PostToolUseFailure/PostToolBatch/PermissionDenied/Notification/SubagentStart/SubagentStop/
  Stop/StopFailure/TeammateIdle/TaskCreated/TaskCompleted/ConfigChange/CwdChanged/DirectoryAdded/
  FileChanged/PreCompact/PostCompact/SessionEnd/Elicitation/ElicitationResult/WorktreeCreate/
  WorktreeRemove), .claude/output-styles/, .mcp.json, plugins
- command/agent/skill: .claude/commands/, .claude/agents/, .claude/skills/

Usage:
    python3 scan_claude_harness.py <project_root> [--user-level] [--md]
"""
import argparse
import json
import os
import re
import sys

HOOK_EVENTS = (
    # Session lifecycle
    "SessionStart", "Setup", "SessionEnd",
    # Instruction loading
    "InstructionsLoaded",
    # User input
    "UserPromptSubmit", "UserPromptExpansion",
    # Display
    "MessageDisplay", "Notification",
    # Tool execution
    "PreToolUse", "PermissionRequest", "PostToolUse",
    "PostToolUseFailure", "PostToolBatch", "PermissionDenied",
    # Agent / subagent lifecycle
    "SubagentStart", "SubagentStop", "Stop", "StopFailure",
    "TeammateIdle",
    # Task management (agent teams)
    "TaskCreated", "TaskCompleted",
    # Configuration changes
    "ConfigChange", "CwdChanged", "DirectoryAdded", "FileChanged",
    # Compaction
    "PreCompact", "PostCompact",
    # Worktree
    "WorktreeCreate", "WorktreeRemove",
    # MCP elicitation
    "Elicitation", "ElicitationResult",
)


def load_json(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            raw = f.read()
        # JSONC tolerance: only strip full-line comments and trailing commas,
        # never '//' inside strings (URLs like https:// would break otherwise).
        raw = re.sub(r"^\s*//[^\n]*", "", raw, flags=re.M)
        raw = re.sub(r"/\*.*?\*/", "", raw, flags=re.S)
        raw = re.sub(r",(\s*[}\]])", r"\1", raw)
        return json.loads(raw), None
    except (OSError, json.JSONDecodeError) as e:
        return None, str(e)


def parse_frontmatter(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
    except OSError:
        return None
    m = re.match(r"^---\s*\n(.*?)\n---\s*\n", text, re.S)
    return m.group(1) if m else None


def fm_field(fm, key):
    if not fm:
        return None
    m = re.search(rf"^{re.escape(key)}\s*:\s*(.+)$", fm, re.M)
    return m.group(1).strip() if m else None


def body_text(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
    except OSError:
        return ""
    m = re.match(r"^---\s*\n.*?\n---\s*\n", text, re.S)
    return (text[m.end():] if m else text).strip()


def collect_md(base, suffixes=(".md",)):
    out = []
    if not os.path.isdir(base):
        return out
    for dirpath, _, files in os.walk(base):
        for fn in sorted(files):
            if any(fn.endswith(s) for s in suffixes):
                out.append(os.path.join(dirpath, fn))
    return out


def summarize_settings(data):
    """Extract the harness-relevant keys from a Claude settings.json."""
    s = {"raw_keys": sorted(data.keys())}
    perms = data.get("permissions") or {}
    s["permissions"] = {
        "allow": len(perms.get("allow") or []),
        "deny": len(perms.get("deny") or []),
        "ask": len(perms.get("ask") or []),
        "allow_rules": (perms.get("allow") or [])[:20],
        "deny_rules": (perms.get("deny") or [])[:20],
        "defaultMode": perms.get("defaultMode"),
        "additionalDirectories": perms.get("additionalDirectories") or [],
    }
    hooks = data.get("hooks") or {}
    s["hooks"] = {}
    for ev in HOOK_EVENTS:
        entries = hooks.get(ev)
        if entries:
            matchers = []
            for grp in entries:
                m = grp.get("matcher", "*")
                cmds = [h.get("command") or h.get("type", "?") for h in (grp.get("hooks") or [])]
                matchers.append({"matcher": m, "commands": cmds})
            s["hooks"][ev] = matchers
    for k in ("model", "env", "statusLine", "outputStyle", "includeCoAuthoredBy",
              "enableAllProjectMcpServers", "enabledMcpjsonServers", "disabledMcpjsonServers",
              "cleanupPeriodDays", "forceLoginMethod",
              "disableAllHooks", "allowedHttpHookUrls", "httpHookAllowedEnvVars",
              "allowManagedHooksOnly", "effort", "sandbox"):
        if k in data:
            v = data[k]
            s[k] = v if k != "env" else {ek: ("***" if re.search(r"key|token|secret|pass", ek, re.I) else ev)
                                         for ek, ev in (v or {}).items()}
    return s


def scan_claude_md_imports(path):
    """Extract @import references from a CLAUDE.md."""
    body = body_text(path) if parse_frontmatter(path) else None
    if body is None:
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                body = f.read()
        except OSError:
            return []
    return re.findall(r"@([\w~./-]+)", body)


def scan_project(root):
    r = {"project_root": os.path.abspath(root)}
    root = r["project_root"]

    # memory: CLAUDE.md anywhere (nested), plus imports
    claude_mds = []
    for dirpath, dirnames, files in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in (".git", "node_modules", "target", "build")]
        for name in ("CLAUDE.md", "CLAUDE.local.md", "AGENTS.md"):
            if name in files:
                p = os.path.join(dirpath, name)
                claude_mds.append({"path": os.path.relpath(p, root),
                                   "size": os.path.getsize(p),
                                   "imports": scan_claude_md_imports(p) if name == "CLAUDE.md" else []})
    r["claude_mds"] = claude_mds

    # instruction: .claude/rules/
    rules_dir = os.path.join(root, ".claude", "rules")
    r["rules_files"] = [{"path": os.path.relpath(p, root)} for p in collect_md(rules_dir)]

    # Rules: settings files
    settings = {}
    for name in ("settings.json", "settings.local.json"):
        p = os.path.join(root, ".claude", name)
        if os.path.isfile(p):
            data, err = load_json(p)
            settings[name] = {"error": err} if err else summarize_settings(data)
        else:
            settings[name] = None
    r["project_settings"] = settings

    # Hooks/extend: commands, agents, skills, output styles
    def md_group(sub, keys):
        base = os.path.join(root, ".claude", sub)
        out = []
        for p in collect_md(base):
            fm = parse_frontmatter(p)
            info = {"path": os.path.relpath(p, root)}
            for k in keys:
                v = fm_field(fm, k)
                if v is not None:
                    info[k] = v
            if len(body_text(p)) < 20:
                info["body_empty"] = True
            out.append(info)
        return out

    r["commands"] = md_group("commands", ("description", "argument-hint", "model", "allowed-tools"))
    r["agents"] = md_group("agents", ("name", "description", "tools", "model"))
    r["skills"] = md_group("skills", ("name", "description", "allowed-tools"))
    r["output_styles"] = md_group("output-styles", ("name", "description"))

    # loop.md customization file
    p = os.path.join(root, ".claude", "loop.md")
    r["loop_md"] = {"path": ".claude/loop.md", "size": os.path.getsize(p)} if os.path.isfile(p) else None

    # MCP: .mcp.json
    p = os.path.join(root, ".mcp.json")
    if os.path.isfile(p):
        data, err = load_json(p)
        if err:
            r["mcp"] = {"path": ".mcp.json", "error": err}
        else:
            servers = list((data.get("mcpServers") or {}).keys())
            r["mcp"] = {"path": ".mcp.json", "servers": servers}
    else:
        r["mcp"] = None

    return r


def managed_settings_candidates():
    """Enterprise managed settings paths per OS."""
    return {
        "macOS": "/Library/Application Support/ClaudeCode/managed-settings.json",
        "Linux/WSL": "/etc/claude-code/managed-settings.json",
        "Windows": os.path.join(os.environ.get("PROGRAMDATA", r"C:\ProgramData"),
                                "ClaudeCode", "managed-settings.json"),
    }


def scan_user_level():
    u = {}
    home = os.path.expanduser("~")
    cdir = os.path.join(home, ".claude")

    # Enterprise managed settings (existence probe, all OSes)
    u["managed_settings"] = {os_name: p for os_name, p in managed_settings_candidates().items()
                             if os.path.isfile(p)}

    # ~/.claude/CLAUDE.md
    p = os.path.join(cdir, "CLAUDE.md")
    u["user_claude_md"] = ({"path": p, "size": os.path.getsize(p),
                            "imports": scan_claude_md_imports(p)} if os.path.isfile(p) else None)

    # ~/.claude/rules/ (user-level instruction rules)
    rules_dir = os.path.join(cdir, "rules")
    u["user_rules"] = [p for p in collect_md(rules_dir)]

    # ~/.claude/settings.json
    p = os.path.join(cdir, "settings.json")
    if os.path.isfile(p):
        data, err = load_json(p)
        u["user_settings"] = {"error": err} if err else summarize_settings(data)
    else:
        u["user_settings"] = None

    # ~/.claude.json (user-level MCP servers + global state; large file, extract keys only)
    p = os.path.join(home, ".claude.json")
    if os.path.isfile(p):
        data, err = load_json(p)
        if err:
            u["claude_json"] = {"error": err}
        else:
            u["claude_json"] = {"mcpServers": list((data.get("mcpServers") or {}).keys())}
    else:
        u["claude_json"] = None

    # user commands/agents/skills/plugins
    def md_group(sub, keys):
        out = []
        for p in collect_md(os.path.join(cdir, sub)):
            fm = parse_frontmatter(p)
            info = {"path": p}
            for k in keys:
                v = fm_field(fm, k)
                if v is not None:
                    info[k] = v
            out.append(info)
        return out

    u["user_commands"] = md_group("commands", ("description",))
    u["user_agents"] = md_group("agents", ("name", "description"))
    u["user_skills"] = md_group("skills", ("name", "description"))
    u["user_output_styles"] = md_group("output-styles", ("name", "description"))
    u["user_plugins_dir"] = os.path.isdir(os.path.join(cdir, "plugins"))

    # user-level loop.md
    p = os.path.join(cdir, "loop.md")
    u["user_loop_md"] = {"path": "~/.claude/loop.md", "size": os.path.getsize(p)} if os.path.isfile(p) else None
    return u


def build_matrix(r):
    user_scanned = "user" in r
    u = r.get("user") or {}

    def cell(items, scanned=True):
        if not scanned:
            return "*(未扫描，加 `--user-level`)*"
        return "<br>".join(items) if items else "—"

    # ---- Project ----
    C = {k: {"p": [], "u": []} for k in ("memory", "agent", "instruction", "skill", "command", "hook", "setting")}
    for f in r["claude_mds"]:
        imp = f"（imports: {', '.join(f['imports'])}）" if f.get("imports") else ""
        C["memory"]["p"].append(f"`{f['path']}`{imp}")
    for f in r["rules_files"]:
        C["instruction"]["p"].append(f"`{f['path']}`")
    for f in r["agents"]:
        C["agent"]["p"].append(f"`{f.get('name') or os.path.basename(f['path'])}`" + (" ⚠️空正文" if f.get("body_empty") else ""))
    for f in r["skills"]:
        C["skill"]["p"].append(f"`{f.get('name') or os.path.basename(os.path.dirname(f['path']))}`" + (" ⚠️空正文" if f.get("body_empty") else ""))
    for name, s in r["project_settings"].items():
        if not s or s.get("error"):
            if s and s.get("error"):
                C["setting"]["p"].append(f"`.claude/{name}` ⚠️解析失败")
            continue
        p = s["permissions"]
        if p["allow"] or p["deny"] or p["ask"]:
            C["setting"]["p"].append(f"`.claude/{name}` permissions（allow {p['allow']}/deny {p['deny']}/ask {p['ask']}）")
        if s.get("model"):
            C["setting"]["p"].append(f"`model` = `{s['model']}`（{name}）")
        if s.get("env"):
            C["setting"]["p"].append(f"`env` {len(s['env'])} 项（{name}）")
        if s.get("statusLine"):
            C["setting"]["p"].append("`statusLine` 已配置")
        for ev, matchers in (s.get("hooks") or {}).items():
            for m in matchers:
                cmds = "; ".join(c[:60] for c in m["commands"])
                C["hook"]["p"].append(f"hook `{ev}` matcher=`{m['matcher']}` → {cmds}（{name}）")
    for f in r["commands"]:
        C["command"]["p"].append(f"command `/{os.path.basename(f['path']).replace('.md','')}`")
    for f in r["output_styles"]:
        C["hook"]["p"].append(f"output style `{f.get('name') or os.path.basename(f['path'])}`")
    if r["mcp"] and not r["mcp"].get("error"):
        C["hook"]["p"].append(f"MCP `.mcp.json`（{', '.join(r['mcp']['servers']) or '空'}）")

    # ---- User ----
    if user_scanned:
        if u.get("user_claude_md"):
            C["memory"]["u"].append("`~/.claude/CLAUDE.md`")
        for f in u.get("user_rules") or []:
            C["instruction"]["u"].append(f"`{f}`")
        for f in u.get("user_agents") or []:
            C["agent"]["u"].append(f"`{f.get('name') or f['path']}`")
        for f in u.get("user_skills") or []:
            C["skill"]["u"].append(f"`{f.get('name') or f['path']}`")
        us = u.get("user_settings")
        if us and not us.get("error"):
            p = us["permissions"]
            if p["allow"] or p["deny"]:
                C["setting"]["u"].append(f"user permissions（allow {p['allow']}/deny {p['deny']}）")
            if us.get("model"):
                C["setting"]["u"].append(f"user `model` = `{us['model']}`")
            if us.get("env"):
                C["setting"]["u"].append(f"user `env` {len(us['env'])} 项")
            for ev, matchers in (us.get("hooks") or {}).items():
                for m in matchers:
                    C["hook"]["u"].append(f"user hook `{ev}` matcher=`{m['matcher']}`")
        cj = u.get("claude_json")
        if cj and cj.get("mcpServers"):
            C["hook"]["u"].append(f"MCP（~/.claude.json）：{', '.join(cj['mcpServers'])}")
        for f in u.get("user_commands") or []:
            C["command"]["u"].append(f"command `/{os.path.basename(f['path']).replace('.md','')}`")
        for f in u.get("user_output_styles") or []:
            C["hook"]["u"].append(f"output style `{f.get('name') or f['path']}`")
        if u.get("user_plugins_dir"):
            C["hook"]["u"].append("`~/.claude/plugins/`")

    L = ["## 0. Harness 分类矩阵（Scope × Category）\n",
         "> **memory** = 自动注入的记忆文件（CLAUDE.md 系，含 @imports）；**instruction** = 指令规则（.claude/rules/ 与 ~/.claude/rules/）；"
         "**agent** = 子代理；**skill** = 按需加载技能；**command** = 手动触发的 slash commands（`/cmd`）；"
         "**hook** = 正式 hooks 与触发/扩展机制；**setting** = 权限/行为约束。\n",
         "| 类别 \\ 范围 | **Project（仓库级）** | **User（用户级）** |",
         "|---|---|---|"]
    labels = {"memory": "**memory**（记忆文件）", "agent": "**agent**（子代理）",
              "instruction": "**instruction**（指令规则）", "skill": "**skill**（技能）",
              "command": "**command**（slash 命令）", "hook": "**hook**（钩子/扩展）",
              "setting": "**setting**（权限/约束）"}
    for k in ("memory", "agent", "instruction", "skill", "command", "hook", "setting"):
        L.append(f"| {labels[k]} | {cell(C[k]['p'])} | {cell(C[k]['u'], user_scanned)} |")
    ms = (r.get("user") or {}).get("managed_settings") or {}
    ms_note = (f"⚠️ 本机检测到 managed settings：{'、'.join(f'{k}（`{v}`）' for k, v in ms.items())}，其配置优先级最高，未在矩阵中展开。"
               if ms else "本机未检测到 managed settings。")
    L.append("\n> Enterprise managed settings 优先级最高（macOS `/Library/Application Support/ClaudeCode/managed-settings.json`、"
             f"Linux/WSL `/etc/claude-code/managed-settings.json`、Windows `C:\\ProgramData\\ClaudeCode\\managed-settings.json`）。{ms_note}")
    return L


def settings_detail(name, s):
    L = [f"\n### `.claude/{name}`"]
    if s is None:
        L.append("- 不存在")
        return L
    if s.get("error"):
        L.append(f"- ⚠️ 解析失败：{s['error']}")
        return L
    p = s["permissions"]
    L.append(f"- permissions：allow={p['allow']} deny={p['deny']} ask={p['ask']}" +
             (f"，defaultMode=`{p['defaultMode']}`" if p.get("defaultMode") else ""))
    for rule in p["deny_rules"]:
        L.append(f"  - deny: `{rule}`")
    for rule in p["allow_rules"][:10]:
        L.append(f"  - allow: `{rule}`")
    for ev, matchers in (s.get("hooks") or {}).items():
        L.append(f"- hooks `{ev}`：{len(matchers)} 组 matcher")
    for k in ("model", "env", "statusLine", "outputStyle", "enableAllProjectMcpServers",
              "enabledMcpjsonServers", "disabledMcpjsonServers", "includeCoAuthoredBy",
              "disableAllHooks", "allowedHttpHookUrls", "httpHookAllowedEnvVars",
              "allowManagedHooksOnly"):
        if k in s:
            L.append(f"- `{k}` = `{json.dumps(s[k], ensure_ascii=False)}`")
    if not p["allow"] and not p["deny"] and not s.get("hooks") and len(s.get("raw_keys", [])) <= 1:
        L.append("- 无实质 harness 配置")
    return L


def to_markdown(r):
    L = [f"# Claude Code Harness 配置扫描报告\n", f"项目根目录：`{r['project_root']}`\n"]
    L.extend(build_matrix(r))

    # ---- 明细章节：按 category 顺序（与矩阵七行一一对应） ----

    L.append("\n## 1. memory（CLAUDE.md 记忆体系）")
    if r["claude_mds"]:
        for f in r["claude_mds"]:
            imp = f" — imports: {', '.join(f['imports'])}" if f.get("imports") else ""
            L.append(f"- `{f['path']}` ({f['size']} bytes){imp}")
    else:
        L.append("- ❌ 无 CLAUDE.md")

    L.append("\n## 2. agent（Subagents .claude/agents/）")
    L.extend([f"- `{f['path']}`" + (f" — {f.get('name')}: {f.get('description','')[:60]}" if f.get("name") else "")
              + (" ⚠️ 空正文" if f.get("body_empty") else "") for f in r["agents"]] or ["- 无"])

    L.append("\n## 3. instruction（.claude/rules/）")
    L.extend([f"- `{f['path']}`" for f in r["rules_files"]] or ["- 无"])

    L.append("\n## 4. skill（.claude/skills/）")
    L.extend([f"- `{f['path']}`" + (" ⚠️ 空正文" if f.get("body_empty") else "") for f in r["skills"]] or ["- 无"])

    L.append("\n## 5. command（Slash commands .claude/commands/）")
    L.extend([f"- `/{os.path.basename(f['path']).replace('.md','')}` ← `{f['path']}`" +
              (f" — allowed-tools: {f['allowed-tools']}" if f.get("allowed-tools") else "")
              for f in r["commands"]] or ["- 无"])

    L.append("\n## 6. hook（Hooks / output styles / MCP）")
    found = False
    for name, s in r["project_settings"].items():
        if s and not s.get("error"):
            for ev, matchers in (s.get("hooks") or {}).items():
                found = True
                for m in matchers:
                    L.append(f"- hook `{ev}` matcher=`{m['matcher']}` → {'; '.join(m['commands'])}（{name}）")
    if not found:
        L.append("- 未配置任何 hooks")
    for f in r["output_styles"]:
        L.append(f"- output style `{f.get('name') or os.path.basename(f['path'])}`")
    m = r["mcp"]
    if m is None:
        L.append("- MCP：无")
    elif m.get("error"):
        L.append(f"- MCP ⚠️ 解析失败：{m['error']}")
    else:
        L.append(f"- MCP `.mcp.json` — servers: {', '.join(m['servers']) or '(空)'}")

    L.append("\n## 7. setting（settings.json 权限与行为约束）")
    for name, s in r["project_settings"].items():
        L.extend(settings_detail(name, s))

    if "user" in r:
        u = r["user"]
        L.append("\n## 8. 用户级明细（User scope）")
        ucm = u.get("user_claude_md")
        L.append(f"- `~/.claude/CLAUDE.md`：{'存在' if ucm else '未配置'}")
        for f in u.get("user_rules") or []:
            L.append(f"- 用户级规则：`{f}`")
        us = u.get("user_settings")
        if us and not us.get("error"):
            p = us["permissions"]
            L.append(f"- user settings：allow={p['allow']} deny={p['deny']}，hooks={len(us.get('hooks') or {})} 类事件")
        elif us and us.get("error"):
            L.append(f"- user settings ⚠️ 解析失败：{us['error']}")
        cj = u.get("claude_json")
        if cj and cj.get("mcpServers"):
            L.append(f"- user MCP（~/.claude.json）：{', '.join(cj['mcpServers'])}")
        for grp, label in (("user_commands", "command"), ("user_agents", "subagent"),
                           ("user_skills", "skill"), ("user_output_styles", "output style")):
            for f in u.get(grp) or []:
                L.append(f"- user {label}：`{f['path']}`")

    # ---- Loop Engineering 诊断 ----
    L.append("\n## 9. Loop Engineering 诊断")
    L.append("> 控制 agentic loop（推理→行动→观察→重复）行为的关键配置。")
    le_items = []

    # loop.md
    if r.get("loop_md"):
        le_items.append(f"- **loop.md**：`{r['loop_md']['path']}`（{r['loop_md']['size']} bytes）— 自定义 /loop 默认 prompt")
    if "user" in r and r["user"].get("user_loop_md"):
        ulm = r["user"]["user_loop_md"]
        le_items.append(f"- **loop.md**（用户级）：`{ulm['path']}`（{ulm['size']} bytes）")

    # Collect from all settings sources
    all_settings = []
    for name, s in r.get("project_settings", {}).items():
        if s and not s.get("error"):
            all_settings.append((name, s))
    if "user" in r:
        us = r["user"].get("user_settings")
        if us and not us.get("error"):
            all_settings.append(("user", us))

    for name, s in all_settings:
        # effort
        if s.get("effort"):
            tag = " ⚠️ max 成本高" if str(s["effort"]).lower() == "max" else ""
            le_items.append(f"- **effort** = `{s['effort']}`（{name}）{tag}")
        # sandbox
        if s.get("sandbox"):
            le_items.append(f"- **sandbox** = `{json.dumps(s['sandbox'], ensure_ascii=False)}`（{name}）")
        # defaultMode
        p = s.get("permissions") or {}
        dm = p.get("defaultMode")
        if dm:
            tag = " ⚠️ 循环全自动" if dm == "bypassPermissions" else ""
            le_items.append(f"- **defaultMode** = `{dm}`（{name}）{tag}")
        # disableAllHooks
        if s.get("disableAllHooks"):
            le_items.append(f"- **disableAllHooks** = `true`（{name}）⚠️ 所有 hooks 静默失效")
        # env loop-related vars
        env = s.get("env") or {}
        for ek in ("CLAUDE_CODE_DISABLE_CRON", "BASH_MAX_TIMEOUT_MS", "BASH_DEFAULT_TIMEOUT_MS"):
            if ek in env:
                val = env[ek]
                tag = ""
                if ek == "CLAUDE_CODE_DISABLE_CRON" and str(val) == "1":
                    tag = " ⚠️ /loop 和 cron 工具不可用"
                le_items.append(f"- **{ek}** = `{val}`（{name}）{tag}")
        # Stop/SubagentStop hooks (runaway guard relevant)
        hooks = s.get("hooks") or {}
        for ev in ("Stop", "SubagentStop"):
            if ev in hooks:
                le_items.append(f"- **hook:{ev}** = {len(hooks[ev])} 组（{name}）— 可 block 强制继续，连续 8 次后自动放行")

    if not le_items:
        le_items.append("- 未检测到 loop engineering 相关配置（全部使用默认值）")
    L.extend(le_items)

    return "\n".join(L) + "\n"


def main():
    ap = argparse.ArgumentParser(description="Scan Claude Code harness settings of a project")
    ap.add_argument("project_root")
    ap.add_argument("--user-level", action="store_true")
    ap.add_argument("--md", action="store_true")
    args = ap.parse_args()

    if not os.path.isdir(args.project_root):
        print(f"error: {args.project_root} is not a directory", file=sys.stderr)
        sys.exit(2)

    result = scan_project(args.project_root)
    if args.user_level:
        result["user"] = scan_user_level()

    if args.md:
        print(to_markdown(result))
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
