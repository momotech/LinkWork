"""Workspace AGENTS.md helper utilities."""

from __future__ import annotations

from pathlib import Path

AGENTS_FILE_PATH = Path("/workspace/AGENTS.md")
AGENTS_DEFAULT_HEADER = "# AGENTS Workspace Guide"
SKILL_GUIDANCE_HEADER = "## Skill 使用规范（Claude Native）"
SKILL_GUIDANCE_BLOCK = """## Skill 使用规范（Claude Native）

1. Skill 是“流程指引”，MCP 是“外部能力”。
   - 仅加载 Skill 不代表具备外部检索能力。
   - 若 Skill 依赖 Tavily，必须确保已绑定并加载 Tavily MCP。

2. loaded != referenced。
   - `SKILLS_LOADED` 仅表示已加载。
   - 只有实际调用 `Skill` 工具或读取 Skill 文件，才算真正引用。

3. 禁止 slash 命令话术。
   - 不输出 `/commit`、`/review-pr` 等样式。
   - 统一使用自然语言和结构化结论表达。

4. 大结果检索必须走分批流程。
   - Tavily 多查询结果先落地 `/tmp/*.json`。
   - 再做“去重压缩 → 分批小结 → 最终汇总”，避免上下文或超时问题。

5. 产物规范。
   - `/tmp` 仅临时文件，不作为最终交付。
   - 最终文件必须写入 `/workspace/workstation`。

6. 能力降级时必须显式说明。
   - 若 MCP 不可用或证据不足，明确“待确认项”和下一步动作，禁止编造结论。"""


def ensure_workspace_agents_skill_guidance(path: Path = AGENTS_FILE_PATH) -> None:
    """Ensure AGENTS.md contains skill guidance section."""
    path.parent.mkdir(parents=True, exist_ok=True)
    existing = path.read_text(encoding="utf-8") if path.exists() else ""
    updated = upsert_workspace_agents_skill_guidance(existing)
    if updated != existing:
        path.write_text(updated, encoding="utf-8")


def upsert_workspace_agents_skill_guidance(content: str) -> str:
    """Append skill guidance section when missing."""
    normalized = content.replace("\r\n", "\n")
    if SKILL_GUIDANCE_HEADER in normalized:
        return normalized

    prefix = normalized.strip()
    if not prefix:
        return f"{AGENTS_DEFAULT_HEADER}\n\n{SKILL_GUIDANCE_BLOCK}\n"
    return f"{prefix}\n\n{SKILL_GUIDANCE_BLOCK}\n"

