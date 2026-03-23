"""Config models for LinkWork Agent SDK."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints
from typing_extensions import Annotated

WorkerName = Annotated[str, StringConstraints(pattern=r"^[a-z0-9-]+$")]


class ClaudeSettingsConfig(BaseModel):
    """Claude runtime settings."""

    model_config = ConfigDict(extra="forbid", strict=True)

    env: dict[str, str] = Field(default_factory=dict)
    model: str = "openrouter/anthropic/claude-sonnet-4.5"
    language: str = "Chinese"


class AgentConfig(BaseModel):
    """Agent behavior settings."""

    model_config = ConfigDict(extra="forbid", strict=True)

    name: WorkerName = "demo-worker"
    max_turns: int = Field(default=100, ge=1)
    max_thinking_tokens: int = Field(default=10_000, ge=0)
    permission_mode: Literal["default", "acceptEdits", "bypassPermissions"] = "default"
    allowed_tools: list[str] = Field(default_factory=list)
    disallowed_tools: list[str] = Field(default_factory=list)
    can_use_tools: list[str] = Field(default_factory=list)
    zz_enabled: bool = Field(default=False, description="启用 zz 安全执行代理（Bash 命令经 zzd 安全审计）")


class SystemPromptConfig(BaseModel):
    """System prompt settings."""

    model_config = ConfigDict(extra="forbid", strict=True)

    use_preset: bool = True
    preset: Literal["claude_code"] = "claude_code"
    append: str = ""


class LinkWorkAgentSDKConfig(BaseModel):
    """Top-level SDK config."""

    model_config = ConfigDict(extra="forbid", strict=True)

    claude_settings: ClaudeSettingsConfig = Field(default_factory=ClaudeSettingsConfig)
    agent: AgentConfig = Field(default_factory=AgentConfig)
    system_prompt: SystemPromptConfig = Field(default_factory=SystemPromptConfig)
