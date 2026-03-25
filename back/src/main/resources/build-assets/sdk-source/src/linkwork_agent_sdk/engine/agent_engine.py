"""Agent engine orchestration for one task session."""

from __future__ import annotations

import asyncio
import base64
import contextlib
import inspect
import json
import logging
import os
import shlex
import shutil
import traceback
import uuid
from collections.abc import AsyncGenerator
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from ..config import ConfigLoader, LinkWorkAgentSDKConfig
from ..constants import (
    ENV_LINKWORK_WATERMARK_NAME,
    ENV_LINKWORK_WATERMARK_OWNER,
    ENV_LINKWORK_WATERMARK_POLICY_URL,
    ENV_LINKWORK_WATERMARK_REPO_URL,
    ENV_TASK_ID,
    ENV_WORKSTATION_ID,
    LOG_FALLBACK_DIR,
    SECURITY_CHECK_TIMEOUT_SECONDS,
    WATERMARK_DEFAULT_OWNER,
    WATERMARK_DEFAULT_POLICY_URL,
    WATERMARK_DEFAULT_PRODUCT,
    WATERMARK_DEFAULT_REPO_URL,
)
from ..exceptions import ConcurrentExecutionError, RuntimeInitError, RuntimeProtocolError
from ..logger import (
    CompositeTransport,
    FileLocalFallback,
    LogEventType,
    LogRecorder,
    RedisStreamTransport,
)
from ..mcp import MCPProvider
from ..redis import RedisClient
from ..security import SecurityAction, SecurityDecision, SecurityEnforcer
from ..skills import Skill, SkillsProvider

_logger = logging.getLogger(__name__)


class AgentEngine:
    """Single-task orchestration engine with strict initialization order."""

    def __init__(
        self,
        config_file: str | Path,
        task_id: str | None = None,
        workstation_id: str | None = None,
        cwd: str | Path | None = None,
        redis_client: RedisClient | None = None,
        session_id: str | None = None,
        runtime_system_prompt_append: str | None = None,
        runtime_model_override: str | None = None,
    ) -> None:
        self._config_file = Path(config_file)
        self._task_id = task_id or os.getenv(ENV_TASK_ID) or f"task-{uuid.uuid4().hex[:8]}"
        self._workstation_id = workstation_id or os.getenv(ENV_WORKSTATION_ID) or "unknown"
        self._cwd = Path(cwd).resolve() if cwd is not None else None

        self._config_loader = ConfigLoader(self._config_file)
        self._config: LinkWorkAgentSDKConfig | None = None

        self._redis_client = redis_client
        self._own_redis_client = redis_client is None
        self._logger: LogRecorder | None = None
        self._security = SecurityEnforcer()
        self._skills = SkillsProvider()
        self._mcp = MCPProvider()

        self._session_id = session_id or f"session-{uuid.uuid4().hex[:10]}"
        self._runtime_client: Any = None
        self._runtime_entered = False
        self._runtime_symbols: dict[str, Any] = {}
        self._runtime_provider = "claude"
        self._runtime_system_prompt_append = (
            runtime_system_prompt_append.strip() if runtime_system_prompt_append else ""
        )
        self._runtime_model_override = (
            runtime_model_override.strip() if runtime_model_override else ""
        )

        self._running_lock = asyncio.Lock()
        self._entered = False
        self._zz_enabled = False
        self._selected_skills_for_run: dict[str, Skill] = {}
        self._referenced_skills_for_run: set[str] = set()
        self._referenced_skill_reads_for_run: set[tuple[str, str]] = set()
        self._referenced_skill_commands_for_run: set[str] = set()
        self._runtime_skills_dir: Path | None = None

    @property
    def task_id(self) -> str:
        return self._task_id

    @property
    def workstation_id(self) -> str:
        return self._workstation_id

    @property
    def session_id(self) -> str:
        return self._session_id

    @property
    def cwd(self) -> Path:
        if self._cwd is not None:
            return self._cwd
        return Path.cwd()

    async def __aenter__(self) -> "AgentEngine":
        if self._entered:
            return self

        try:
            # 1) Config
            self._config = self._config_loader.load()
            self._runtime_provider = self._config.runtime.provider

            # 2) Logger
            if self._redis_client is None:
                self._redis_client = RedisClient()
            if self._own_redis_client:
                await self._redis_client.connect()

            primary_transport = RedisStreamTransport(
                redis_client=self._redis_client,
                workstation_id=self._workstation_id,
                task_id=self._task_id,
            )
            fallback_transport = FileLocalFallback(
                session_id=self._session_id,
                output_dir=LOG_FALLBACK_DIR,
            )
            transport = CompositeTransport(primary=primary_transport, fallback=fallback_transport)
            self._logger = LogRecorder(
                output_dir=LOG_FALLBACK_DIR,
                session_id=self._session_id,
                transport=transport,
            )
            await self._logger.start()
            await self._logger.record(
                LogEventType.CONFIG_LOADED,
                {"config_path": str(self._config_file)},
            )
            await self._logger.record(
                LogEventType.WATERMARK_ATTACHED,
                self._build_identity_watermark_metadata(),
            )

            # 3) Security
            self._security.load()
            self._security.set_can_use_tools(self._config.agent.can_use_tools)
            await self._logger.record(
                LogEventType.SECURITY_LOADED,
                {"rules_count": self._security.rule_count},
            )

            # 3.1) zz_enabled validation
            self._zz_enabled = self._config.agent.zz_enabled
            if self._zz_enabled:
                zz_path = shutil.which("zz")
                if zz_path is None:
                    from ..exceptions import SecurityLoadError
                    raise SecurityLoadError("zz_enabled=true but 'zz' binary not found in PATH")
                _logger.info("zz security proxy enabled: %s", zz_path)

            # 4) Skills
            skills = self._skills.load()
            self._runtime_skills_dir = self._skills.sync_to_claude_project_dir(self.cwd)
            await self._logger.record(
                LogEventType.SKILLS_LOADED,
                {
                    "skills_count": len(skills),
                    "skills_names": self._skills.get_skill_names(),
                    "integration_mode": "setting_sources_project",
                    "runtime_skills_dir": (
                        str(self._runtime_skills_dir) if self._runtime_skills_dir else None
                    ),
                },
            )

            # 5) MCP
            mcp_servers = self._mcp.load()
            self._security.set_available_mcp_servers(set(mcp_servers.keys()))
            await self._logger.record(
                LogEventType.MCP_LOADED,
                {
                    "servers_count": len(mcp_servers),
                    "servers": {
                        name: {
                            "type": cfg.type,
                            "url": cfg.url if cfg.url else None,
                        }
                        for name, cfg in mcp_servers.items()
                    },
                },
            )

            # 6) Runtime
            self._runtime_symbols = self._load_runtime_symbols()
            self._runtime_client = self._build_runtime_client()
            if hasattr(self._runtime_client, "__aenter__"):
                self._runtime_client = await self._runtime_client.__aenter__()
                self._runtime_entered = True

            self._entered = True
            return self
        except Exception:
            await self._cleanup_on_enter_error()
            raise

    async def _cleanup_on_enter_error(self) -> None:
        if self._runtime_client is not None and self._runtime_entered and hasattr(self._runtime_client, "__aexit__"):
            try:
                await self._runtime_client.__aexit__(None, None, None)
            except Exception:
                _logger.warning("failed to close runtime client after __aenter__ error", exc_info=True)

        self._runtime_client = None
        self._runtime_entered = False

        if self._logger is not None:
            try:
                await self._logger.stop()
            except Exception:
                _logger.warning("failed to stop logger after __aenter__ error", exc_info=True)
            self._logger = None

        if self._redis_client is not None and self._own_redis_client:
            try:
                await self._redis_client.close()
            except Exception:
                _logger.warning("failed to close redis after __aenter__ error", exc_info=True)
            self._redis_client = None

        self._config = None
        self._zz_enabled = False
        self._entered = False
        self._runtime_skills_dir = None
        self._runtime_provider = "claude"

    async def __aexit__(self, exc_type: object, exc: object, tb: object) -> None:
        cleanup_error: Exception | None = None

        def capture_cleanup_error(err: Exception) -> None:
            nonlocal cleanup_error
            if cleanup_error is None:
                cleanup_error = err

        if self._runtime_client is not None and self._runtime_entered:
            try:
                await self._runtime_client.__aexit__(exc_type, exc, tb)
            except Exception as error:
                _logger.warning("failed to close runtime client in __aexit__", exc_info=True)
                capture_cleanup_error(error)
            finally:
                self._runtime_client = None
                self._runtime_entered = False

        logger = self._logger
        self._logger = None
        if logger is not None:
            if exc is not None and not isinstance(exc, asyncio.CancelledError):
                try:
                    await logger.record(
                        LogEventType.ERROR,
                        {
                            "error_type": type(exc).__name__,
                            "error_message": str(exc),
                            "stack_trace": "".join(traceback.format_exception(exc)),
                        },
                    )
                except Exception:
                    _logger.warning("failed to record error event in __aexit__", exc_info=True)
            try:
                await logger.stop()
            except Exception as error:
                _logger.warning("failed to stop logger in __aexit__", exc_info=True)
                capture_cleanup_error(error)

        if self._redis_client is not None and self._own_redis_client:
            try:
                await self._redis_client.close()
            except Exception as error:
                _logger.warning("failed to close redis client in __aexit__", exc_info=True)
                capture_cleanup_error(error)
            finally:
                self._redis_client = None

        self._config = None
        self._zz_enabled = False
        self._entered = False
        self._runtime_skills_dir = None
        self._runtime_provider = "claude"

        if exc is None and cleanup_error is not None:
            raise cleanup_error

    async def run(self, task: str) -> AsyncGenerator[Any, None]:
        """Run one task and yield streaming messages from runtime client."""
        if self._runtime_client is None:
            raise RuntimeInitError("Runtime is not initialized")
        if self._running_lock.locked():
            raise ConcurrentExecutionError("Concurrent run() is not allowed")

        async with self._running_lock:
            self._reset_required_skill_state()
            try:
                guarded_task = self._prepare_runtime_task(task)
                await self._record_skill_selected_event()
                await self._runtime_client.query(guarded_task)
                async for message in self._runtime_client.receive_response():
                    await self._record_runtime_message(message)
                    yield message
            finally:
                await self._record_skill_usage_summary_event()
                self._reset_required_skill_state()

    def _load_runtime_symbols(self) -> dict[str, Any]:
        if self._runtime_provider == "pi":
            return {}
        if self._runtime_provider != "claude":
            raise RuntimeInitError(f"Unsupported runtime provider: {self._runtime_provider}")
        return self._load_claude_runtime_symbols()

    def _load_claude_runtime_symbols(self) -> dict[str, Any]:
        try:
            import claude_agent_sdk as sdk  # type: ignore
        except ImportError as error:
            raise RuntimeInitError("claude-agent-sdk is required for runtime") from error

        symbols: dict[str, Any] = {"sdk": sdk}
        for name in (
            "ClaudeSDKClient",
            "ClaudeAgentOptions",
            "HookMatcher",
            "PermissionResultAllow",
            "PermissionResultDeny",
        ):
            symbols[name] = getattr(sdk, name, None)
        return symbols

    def _build_runtime_client(self) -> Any:
        if self._runtime_provider == "pi":
            return self._build_pi_runtime_client()
        if self._runtime_provider != "claude":
            raise RuntimeInitError(f"Unsupported runtime provider: {self._runtime_provider}")
        return self._build_claude_runtime_client()

    def _build_claude_runtime_client(self) -> Any:
        sdk = self._runtime_symbols["sdk"]
        options = self._build_runtime_options()

        client_class = self._runtime_symbols.get("ClaudeSDKClient")
        if client_class is not None:
            return client_class(options=options)

        query_func = getattr(sdk, "query", None)
        if query_func is not None:
            return _QueryRuntimeClient(query_func=query_func, options=options)

        raise RuntimeInitError("No supported runtime client found in claude-agent-sdk")

    def _build_runtime_options(self) -> Any:
        if self._config is None:
            raise RuntimeInitError("Config must be loaded before options build")

        system_prompt = self._build_system_prompt(self._config)
        resolved_env = _resolve_env_placeholders(
            self._config.claude_settings.env,
            workstation_id=self._workstation_id,
            task_id=self._task_id,
        )

        hooks = self._build_hooks()
        allowed_tools = self._build_allowed_tools(self._config.agent.allowed_tools)
        runtime_model = self._runtime_model_override or self._config.claude_settings.model
        options_dict: dict[str, Any] = {
            "system_prompt": system_prompt,
            "allowed_tools": allowed_tools,
            "disallowed_tools": self._config.agent.disallowed_tools,
            "permission_mode": self._config.agent.permission_mode,
            "max_turns": self._config.agent.max_turns,
            "max_thinking_tokens": self._config.agent.max_thinking_tokens,
            "model": runtime_model,
            "cwd": str(self.cwd),
            "mcp_servers": self._mcp.get_mcp_servers_config(),
            "hooks": hooks,
            "can_use_tool": self._can_use_tool,
        }
        # Legacy plugins-based skill wiring is intentionally disabled.
        # Reason: enforce Claude official skill path via setting_sources + Skill tool
        # to avoid runtime "loaded but not recognized" semantic drift.
        # options_dict["plugins"] = self._skills.get_plugins_config()

        options_class = self._runtime_symbols.get("ClaudeAgentOptions")
        supported_option_keys = self._get_runtime_option_keys(options_class)
        self._inject_setting_sources_option(
            options_dict=options_dict,
            supported_option_keys=supported_option_keys,
        )
        if resolved_env:
            options_dict["env"] = resolved_env

        if options_class is not None:
            return options_class(**options_dict)
        return options_dict

    def _build_pi_runtime_client(self) -> Any:
        options = self._build_pi_runtime_options()
        return _PiRPCRuntimeClient(
            cwd=self.cwd,
            model=options["model"],
            env=options["env"],
        )

    def _build_pi_runtime_options(self) -> dict[str, Any]:
        if self._config is None:
            raise RuntimeInitError("Config must be loaded before options build")

        env_source = self._config.pi_settings.env or self._config.claude_settings.env
        resolved_env = _resolve_env_placeholders(
            env_source,
            workstation_id=self._workstation_id,
            task_id=self._task_id,
        )
        runtime_model = (
            self._runtime_model_override
            or self._config.pi_settings.model
            or self._config.claude_settings.model
        )
        return {
            "model": runtime_model.strip(),
            "env": resolved_env,
        }

    def _prepare_runtime_task(self, task: str) -> str:
        guarded_task = self._build_task_with_skill_guard(task)
        if self._runtime_provider != "pi":
            return guarded_task

        if self._config is None:
            return guarded_task

        system_prompt = self._build_pi_system_prompt(self._config).strip()
        if not system_prompt:
            return guarded_task
        return f"{system_prompt}\n\nUser task:\n{guarded_task}"

    def _build_system_prompt(
        self, config: LinkWorkAgentSDKConfig,
    ) -> dict[str, Any] | str:
        """构建 system prompt。

        当 use_preset=True 时，使用 Claude Code 内建预设 + append 追加自定义指令，
        保留 CLI 内建的工作目录感知、文件路径规范等关键上下文。
        当 use_preset=False 时，返回纯字符串自定义 prompt。
        """
        # 收集追加内容
        append_parts: list[str] = []

        # Legacy prompt-based skill exposure is intentionally disabled.
        # Official chain is setting_sources=["project"] with Skill tool.
        # skills_summary = self._skills.get_skill_summary()
        # if skills_summary:
        #     append_parts.append(skills_summary)

        language = config.claude_settings.language.strip()
        if language:
            append_parts.append(
                f"Please respond in {language} unless the user explicitly asks for another language.",
            )
        append_parts.append(self._build_identity_watermark_prompt())

        user_append = config.system_prompt.append.strip()
        if user_append:
            append_parts.append(user_append)

        runtime_append = self._runtime_system_prompt_append.strip()
        if runtime_append:
            append_parts.append(runtime_append)

        append_text = "\n\n".join(p for p in append_parts if p)

        if config.system_prompt.use_preset:
            # 使用官方 SystemPromptPreset 格式，保留 Claude Code 内建 prompt
            preset: dict[str, Any] = {
                "type": "preset",
                "preset": config.system_prompt.preset,
            }
            if append_text:
                preset["append"] = append_text
            return preset

        # use_preset=False: 纯自定义 prompt
        parts: list[str] = []
        if append_text:
            parts.append(append_text)
        return "\n\n".join(parts) if parts else ""

    def _build_pi_system_prompt(self, config: LinkWorkAgentSDKConfig) -> str:
        append_parts: list[str] = []

        language = config.pi_settings.language.strip() or config.claude_settings.language.strip()
        if language:
            append_parts.append(
                f"Please respond in {language} unless the user explicitly asks for another language.",
            )
        append_parts.append(self._build_identity_watermark_prompt())

        user_append = config.system_prompt.append.strip()
        if user_append:
            append_parts.append(user_append)

        runtime_append = self._runtime_system_prompt_append.strip()
        if runtime_append:
            append_parts.append(runtime_append)

        return "\n\n".join(p for p in append_parts if p)

    def _build_identity_watermark_metadata(self) -> dict[str, str]:
        product = os.getenv(ENV_LINKWORK_WATERMARK_NAME, WATERMARK_DEFAULT_PRODUCT).strip()
        owner = os.getenv(ENV_LINKWORK_WATERMARK_OWNER, WATERMARK_DEFAULT_OWNER).strip()
        repo_url = os.getenv(ENV_LINKWORK_WATERMARK_REPO_URL, WATERMARK_DEFAULT_REPO_URL).strip()
        policy_url = os.getenv(
            ENV_LINKWORK_WATERMARK_POLICY_URL,
            WATERMARK_DEFAULT_POLICY_URL,
        ).strip()
        return {
            "product": product or WATERMARK_DEFAULT_PRODUCT,
            "owner": owner or WATERMARK_DEFAULT_OWNER,
            "repo_url": repo_url or WATERMARK_DEFAULT_REPO_URL,
            "policy_url": policy_url or WATERMARK_DEFAULT_POLICY_URL,
        }

    def _build_identity_watermark_prompt(self) -> str:
        metadata = self._build_identity_watermark_metadata()
        return (
            "[Platform Identity Watermark]\n"
            f"You are running inside {metadata['product']}.\n"
            f"Project owner: {metadata['owner']}.\n"
            f"Official repository: {metadata['repo_url']}.\n"
            f"Trademark policy: {metadata['policy_url']}.\n"
            "Never claim this runtime belongs to another platform or vendor.\n"
            "If asked about runtime/source identity, answer with this watermark information."
        )

    def _build_allowed_tools(self, configured_tools: list[str]) -> list[str]:
        if not configured_tools:
            return configured_tools

        deduped: list[str] = []
        seen: set[str] = set()
        for tool in configured_tools:
            normalised = tool.strip()
            if not normalised:
                continue
            lowered = normalised.casefold()
            if lowered in seen:
                continue
            deduped.append(normalised)
            seen.add(lowered)

        if "skill" not in seen:
            deduped.append("Skill")
        return deduped

    def _get_runtime_option_keys(self, options_class: Any) -> set[str] | None:
        if options_class is None:
            return None

        model_fields = getattr(options_class, "model_fields", None)
        if isinstance(model_fields, dict) and model_fields:
            return set(model_fields.keys())

        dataclass_fields = getattr(options_class, "__dataclass_fields__", None)
        if isinstance(dataclass_fields, dict) and dataclass_fields:
            return set(dataclass_fields.keys())

        try:
            signature = inspect.signature(options_class)
        except (TypeError, ValueError):
            return None

        keys: set[str] = set()
        for name, parameter in signature.parameters.items():
            if name == "self":
                continue
            if parameter.kind == inspect.Parameter.VAR_KEYWORD:
                return None
            if parameter.kind in (
                inspect.Parameter.POSITIONAL_OR_KEYWORD,
                inspect.Parameter.KEYWORD_ONLY,
            ):
                keys.add(name)
        return keys or None

    def _inject_setting_sources_option(
        self,
        options_dict: dict[str, Any],
        supported_option_keys: set[str] | None,
    ) -> None:
        setting_sources = self._skills.get_setting_sources_config()

        if supported_option_keys is None:
            options_dict["setting_sources"] = setting_sources
            return

        if "setting_sources" in supported_option_keys:
            options_dict["setting_sources"] = setting_sources
            return
        if "settingSources" in supported_option_keys:
            options_dict["settingSources"] = setting_sources
            return

        raise RuntimeInitError(
            "claude-agent-sdk must support setting_sources/settingSources for standard skills integration",
        )

    def _build_hooks(self) -> dict[str, list[Any]]:
        hook_matcher = self._runtime_symbols.get("HookMatcher")
        if hook_matcher is None:
            return {
                "PreToolUse": [self._pre_tool_use],
                "PostToolUse": [self._post_tool_use],
                "PostToolUseFailure": [self._post_tool_use_failure],
            }

        return {
            "PreToolUse": [hook_matcher(hooks=[self._pre_tool_use])],
            "PostToolUse": [hook_matcher(hooks=[self._post_tool_use])],
            "PostToolUseFailure": [hook_matcher(hooks=[self._post_tool_use_failure])],
        }

    def _reset_required_skill_state(self) -> None:
        self._selected_skills_for_run = {}
        self._referenced_skills_for_run = set()
        self._referenced_skill_reads_for_run = set()
        self._referenced_skill_commands_for_run = set()

    def _resolve_skill_by_name(self, requested_name: str) -> Skill | None:
        skill = self._skills.get_skill(requested_name)
        if skill is not None:
            return skill
        lowered = requested_name.casefold()
        for known_name in self._skills.get_skill_names():
            if known_name.casefold() == lowered:
                return self._skills.get_skill(known_name)
        return None

    def _build_task_with_skill_guard(self, task: str) -> str:
        # 强绑定 guard 已下线，回归 Claude 原生 Skill 生态。
        # 仅保留观测事件，不再改写任务文本。
        self._selected_skills_for_run = {}
        return task

    def _resolve_tool_path(self, tool_input: dict[str, Any]) -> Path | None:
        raw_path = str(
            tool_input.get("file_path")
            or tool_input.get("path")
            or "",
        ).strip()
        if not raw_path:
            return None

        path = Path(raw_path)
        if not path.is_absolute():
            path = self.cwd / path
        return path.resolve(strict=False)

    def _resolve_skill_from_path(self, path: Path) -> Skill | None:
        for skill in self._skills.get_skills():
            skill_file = skill.path.resolve(strict=False)
            source_dir = skill_file.parent
            runtime_dir = (
                (self._runtime_skills_dir / source_dir.name).resolve(strict=False)
                if self._runtime_skills_dir is not None
                else None
            )
            if path == skill_file or path.is_relative_to(source_dir):
                return skill
            if runtime_dir is not None and (path == runtime_dir or path.is_relative_to(runtime_dir)):
                return skill
        return None

    async def _mark_required_skill_usage_from_tool(
        self,
        tool_name: str,
        tool_input: dict[str, Any],
    ) -> None:
        if tool_name == "Skill":
            command_name = str(
                tool_input.get("commandName")
                or tool_input.get("name")
                or tool_input.get("skill_name")
                or "",
            ).strip()
            if not command_name:
                return

            skill = self._resolve_skill_by_name(command_name)
            if skill is None:
                return

            self._referenced_skills_for_run.add(skill.name)

            if skill.name in self._referenced_skill_commands_for_run:
                return
            self._referenced_skill_commands_for_run.add(skill.name)

            if self._logger is None:
                return

            await self._logger.record(
                LogEventType.SKILL_REFERENCED,
                {
                    "skill_name": skill.name,
                    "reference_type": "skill_tool",
                    "command_name": command_name,
                    "source": "loaded",
                },
            )
            return

        if tool_name != "Read":
            return

        path = self._resolve_tool_path(tool_input)
        if path is None:
            return
        skill = self._resolve_skill_from_path(path)
        if skill is None:
            return

        self._referenced_skills_for_run.add(skill.name)

        read_marker = (skill.name, str(path))
        if read_marker in self._referenced_skill_reads_for_run:
            return
        self._referenced_skill_reads_for_run.add(read_marker)

        if self._logger is None:
            return

        await self._logger.record(
            LogEventType.SKILL_REFERENCED,
            {
                "skill_name": skill.name,
                "reference_type": "read",
                "file_path": str(path),
                "source": "loaded",
            },
        )

    async def _record_skill_selected_event(self) -> None:
        if self._logger is None:
            return
        mode = "claude_native"
        await self._logger.record(
            LogEventType.SKILL_SELECTED,
            {
                "mode": mode,
                "loaded_skills_count": len(self._skills.get_skill_names()),
                "loaded_skills": self._skills.get_skill_names(),
                # Claude 原生模式下不再有 SDK 侧“已选”概念，保留兼容字段为空。
                "selected_skills": [],
                "required_skills": [],
                "suggested_skills": [],
            },
        )

    async def _record_skill_usage_summary_event(self) -> None:
        if self._logger is None:
            return

        mode = "claude_native"
        referenced_names = sorted(self._referenced_skills_for_run)
        await self._logger.record(
            LogEventType.SKILL_USAGE_SUMMARY,
            {
                "mode": mode,
                # Claude 原生模式下不再统计 SDK 侧“已选/缺失”，避免与强绑定语义混淆。
                "selected_skills": [],
                "referenced_skills": referenced_names,
                "missing_selected_skills": [],
                "missing_required_skills": [],
            },
        )

    async def _pre_tool_use(self, input_data: dict[str, Any], *_: Any, **__: Any) -> dict[str, Any]:
        tool_name = str(input_data.get("tool_name", "")).strip()
        tool_input = _safe_dict(input_data.get("tool_input"))
        await self._mark_required_skill_usage_from_tool(tool_name=tool_name, tool_input=tool_input)

        if self._logger is not None:
            event_data: dict[str, Any] = {"tool_name": tool_name, "tool_input": tool_input}
            # 标记 MCP 工具来源，前端可据此区分展示
            if tool_name.startswith("mcp__"):
                parts = tool_name.split("__", 2)
                if len(parts) == 3:
                    event_data["source"] = "mcp"
                    event_data["mcp_server"] = parts[1]
                    event_data["mcp_tool"] = parts[2]
            await self._logger.record(
                LogEventType.TOOL_CALL,
                event_data,
            )

        # 安全主入口：PreToolUse 在 bypassPermissions 模式下依然会执行。
        if not tool_name:
            return self._build_pre_tool_use_deny("Tool name could not be determined")

        decision = await self._evaluate_security_decision(tool_name=tool_name, tool_input=tool_input)
        if decision.action == SecurityAction.DENY:
            message = decision.message or "Denied by security policy"
            return self._build_pre_tool_use_deny(message)

        # zz 拦截：zz_enabled + Bash 工具 → updatedInput 透明改写
        if self._zz_enabled and tool_name == "Bash":
            command = self._extract_bash_command(tool_input)
            if self._is_manual_zz_invocation(command):
                return self._build_pre_tool_use_deny("入口由系统自动路由，不允许手动调用 zz/zzd")
            return await self._build_zz_updated_input(tool_input)

        return {}

    def _build_pre_tool_use_deny(self, message: str) -> dict[str, Any]:
        return {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": message,
            },
        }

    async def _evaluate_security_decision(
        self,
        tool_name: str,
        tool_input: dict[str, Any],
    ) -> SecurityDecision:
        try:
            decision = await asyncio.wait_for(
                asyncio.to_thread(
                    self._security.check,
                    tool_name=tool_name,
                    tool_input=tool_input,
                ),
                timeout=SECURITY_CHECK_TIMEOUT_SECONDS,
            )
        except TimeoutError:
            decision = SecurityDecision(
                action=SecurityAction.DENY,
                rule_id="security_timeout",
                message=(
                    "Security check timeout after "
                    f"{SECURITY_CHECK_TIMEOUT_SECONDS}s (fail-closed DENY)"
                ),
            )
            _logger.warning(
                "security check timeout: tool=%s timeout=%ss",
                tool_name,
                SECURITY_CHECK_TIMEOUT_SECONDS,
            )

        if self._logger is not None:
            event_type = (
                LogEventType.SECURITY_ALLOW
                if decision.action == SecurityAction.ALLOW
                else LogEventType.SECURITY_DENY
            )
            await self._logger.record(
                event_type,
                {
                    "tool_name": tool_name,
                    "rule_id": decision.rule_id,
                    "reason": decision.message,
                },
            )

        return decision

    @staticmethod
    def _extract_bash_command(tool_input: Any) -> str:
        if isinstance(tool_input, dict):
            return str(tool_input.get("command", "") or "")
        if isinstance(tool_input, str):
            return tool_input
        return ""

    @staticmethod
    def _first_command_token(tokens: list[str]) -> str:
        """Return the first executable token while skipping env-style assignments."""
        for token in tokens:
            if token == "env":
                continue
            if (
                "=" in token
                and not token.startswith("-")
                and token.split("=", 1)[0]
                and "/" not in token.split("=", 1)[0]
            ):
                continue
            return token
        return ""

    def _is_manual_zz_invocation(self, command: str) -> bool:
        stripped = command.strip()
        if not stripped:
            return False

        first_segment = stripped
        for separator in ("||", "&&", "|", ";"):
            if separator in first_segment:
                first_segment = first_segment.split(separator, 1)[0]
        first_segment = first_segment.strip()

        try:
            tokens = shlex.split(first_segment, posix=True)
        except ValueError:
            tokens = first_segment.split()
        if not tokens:
            return False

        token = self._first_command_token(tokens)
        if not token:
            return False
        return os.path.basename(token) in {"zz", "zzd"}

    async def _build_zz_updated_input(self, tool_input: Any) -> dict[str, Any]:
        """构造 updatedInput，将 Bash 命令改写为 zz --stdin --raw 管道。

        改写后 claude-code CLI 原生执行管道命令，zzd 成为真正的命令执行者。
        Agent（大模型）不感知改写，只看到原始命令和最终结果。
        """
        command = self._extract_bash_command(tool_input)
        if not command:
            _logger.warning("zz: empty command, skipping rewrite")
            return {}

        # 构造 stdin JSON
        stdin_payload = json.dumps({
            "command": command,
            "task_id": self._task_id,
            "work_dir": str(self.cwd),
            "timeout": 300,  # 5 min default
        })

        # base64 编码（避免 shell 转义问题）
        b64_payload = base64.b64encode(stdin_payload.encode("utf-8")).decode("ascii")

        # 改写命令：echo <base64> | base64 -d | zz --stdin --raw
        rewritten_command = f"echo {b64_payload} | base64 -d | zz --stdin --raw"

        # 记录 ZZ_RESULT（改写发生在执行前，此时没有 request_id）
        await self._log_zz_result(command)

        # 返回 hookSpecificOutput.updatedInput，claude-code CLI 将执行改写后的命令
        # 不设置 permissionDecision，让 can_use_tool 继续处理权限
        return {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "updatedInput": {"command": rewritten_command},
            }
        }

    async def _log_zz_result(self, command: str) -> None:
        """记录 ZZ_RESULT 桥接事件（执行前记录，仅含 command + task_id）."""
        if self._logger is None:
            return
        await self._logger.record(
            LogEventType.ZZ_RESULT,
            {
                "task_id": self._task_id,
                "command": command,
            },
        )

    async def _post_tool_use(self, input_data: dict[str, Any], *_: Any, **__: Any) -> dict[str, Any]:
        tool_name = str(input_data.get("tool_name", "")).strip()
        data: dict[str, Any] = {
            "tool_name": tool_name,
            "response": input_data.get("tool_response"),
        }
        # 标记 MCP 工具来源
        if tool_name.startswith("mcp__"):
            parts = tool_name.split("__", 2)
            if len(parts) == 3:
                data["source"] = "mcp"
                data["mcp_server"] = parts[1]
                data["mcp_tool"] = parts[2]

        if self._logger is not None:
            await self._logger.record(LogEventType.TOOL_RESULT, data)
        return {"continue_": True}

    async def _post_tool_use_failure(self, input_data: dict[str, Any], *_: Any, **__: Any) -> dict[str, Any]:
        """记录工具执行失败（含 zzd DENY）的审计日志。

        PostToolUseFailure 在工具执行出错时触发，典型场景：
        - zz --raw 返回 DENY（exit 1 + stderr）
        - 命令超时或异常退出
        """
        data: dict[str, Any] = {
            "tool_name": input_data.get("tool_name"),
            "tool_input": input_data.get("tool_input"),
            "error": input_data.get("error"),
        }

        if self._logger is not None:
            await self._logger.record(LogEventType.TOOL_ERROR, data)
        return {"continue_": True}

    async def _can_use_tool(self, *args: Any, **kwargs: Any) -> Any:
        tool_name, tool_input = _extract_tool_context(args, kwargs)

        # Security: empty tool_name means extraction failed — deny by default
        if not tool_name:
            _logger.warning("_can_use_tool: empty tool_name, denying by default")
            permission_deny = self._runtime_symbols.get("PermissionResultDeny")
            if permission_deny is not None:
                return permission_deny(message="Tool name could not be determined")
            return False

        decision = await self._evaluate_security_decision(tool_name=tool_name, tool_input=tool_input)

        permission_allow = self._runtime_symbols.get("PermissionResultAllow")
        permission_deny = self._runtime_symbols.get("PermissionResultDeny")

        if decision.action == SecurityAction.DENY:
            if permission_deny is not None:
                return permission_deny(message=decision.message or "Denied by security policy")
            return False

        if permission_allow is not None:
            return permission_allow()
        return True

    async def _record_runtime_message(self, message: Any) -> None:
        if self._logger is None:
            return
        blocks = getattr(message, "content", None)
        if not isinstance(blocks, list):
            return

        for block in blocks:
            thinking = getattr(block, "thinking", None)
            if isinstance(thinking, str) and thinking:
                await self._logger.record(LogEventType.THINKING, {"thinking": thinking})

            text = getattr(block, "text", None)
            if isinstance(text, str) and text:
                await self._logger.record(LogEventType.ASSISTANT_TEXT, {"text": text})
                api_error = _extract_runtime_api_error(text)
                if api_error is not None:
                    raise RuntimeProtocolError(api_error)

class _QueryRuntimeClient:
    """Fallback adapter when SDK only exposes query() coroutine-generator API."""

    def __init__(self, query_func: Any, options: Any) -> None:
        self._query_func = query_func
        self._options = options
        self._iterator: Any = None

    async def query(self, task: str) -> None:
        self._iterator = self._query_func(prompt=task, options=self._options)

    async def receive_response(self):
        if self._iterator is None:
            return
        async for message in self._iterator:
            yield message


class _PiRPCRuntimeClient:
    """RPC client adapter for pi CLI."""

    def __init__(
        self,
        cwd: Path,
        model: str,
        env: dict[str, str],
    ) -> None:
        self._cwd = cwd
        self._model = model
        self._env = env
        self._process: asyncio.subprocess.Process | None = None
        self._stderr_task: asyncio.Task[None] | None = None
        self._stderr_buffer: list[str] = []
        self._request_id: str | None = None

    async def __aenter__(self) -> "_PiRPCRuntimeClient":
        pi_path = shutil.which("pi")
        if not pi_path:
            raise RuntimeInitError("runtime.provider=pi requires 'pi' CLI in PATH")

        cmd = [pi_path, "--mode", "rpc", "--no-session"]
        if self._model:
            cmd.extend(["--model", self._model])

        env = os.environ.copy()
        env.update(self._env)
        self._process = await asyncio.create_subprocess_exec(
            *cmd,
            cwd=str(self._cwd),
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=env,
        )
        self._stderr_task = asyncio.create_task(self._drain_stderr())
        return self

    async def __aexit__(self, exc_type: object, exc: object, tb: object) -> None:
        process = self._process
        self._process = None

        if process is not None and process.returncode is None:
            process.terminate()
            try:
                await asyncio.wait_for(process.wait(), timeout=5)
            except TimeoutError:
                process.kill()
                await process.wait()

        if self._stderr_task is not None:
            self._stderr_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._stderr_task
            self._stderr_task = None

    async def query(self, task: str) -> None:
        process = self._process
        if process is None or process.stdin is None:
            raise RuntimeInitError("pi runtime is not initialized")

        self._request_id = f"req-{uuid.uuid4().hex[:8]}"
        payload = {
            "id": self._request_id,
            "type": "prompt",
            "message": task,
        }
        process.stdin.write((json.dumps(payload, ensure_ascii=False) + "\n").encode("utf-8"))
        await process.stdin.drain()

    async def receive_response(self):
        process = self._process
        if process is None or process.stdout is None:
            raise RuntimeInitError("pi runtime is not initialized")

        while True:
            line = await process.stdout.readline()
            if not line:
                break

            raw = line.decode("utf-8", errors="replace").strip()
            if not raw:
                continue

            try:
                event = json.loads(raw)
            except json.JSONDecodeError:
                _logger.warning("pi rpc emitted non-json line: %s", raw[:500])
                continue

            event_type = str(event.get("type", "")).strip()
            if event_type == "response":
                if (
                    self._request_id
                    and event.get("id") == self._request_id
                    and event.get("command") == "prompt"
                    and not bool(event.get("success"))
                ):
                    error = str(event.get("error", "")).strip() or "unknown error"
                    raise RuntimeProtocolError(f"pi rpc prompt failed: {error}")
                continue

            if event_type == "agent_end":
                return

            if event_type in {"message_update", "message_end"}:
                message = _pi_event_to_runtime_message(event)
                if message is not None:
                    yield message
                continue

            if event_type == "error":
                error = str(event.get("error", "")).strip() or raw
                raise RuntimeProtocolError(f"pi rpc error: {error}")

        return_code = await process.wait()
        if return_code != 0:
            raise RuntimeProtocolError(
                f"pi rpc exited with code {return_code}: {self._stderr_tail()}",
            )

    async def _drain_stderr(self) -> None:
        process = self._process
        if process is None or process.stderr is None:
            return

        while True:
            line = await process.stderr.readline()
            if not line:
                return
            text = line.decode("utf-8", errors="replace").strip()
            if not text:
                continue
            self._stderr_buffer.append(text)
            if len(self._stderr_buffer) > 200:
                self._stderr_buffer = self._stderr_buffer[-200:]

    def _stderr_tail(self) -> str:
        if not self._stderr_buffer:
            return "no stderr output"
        return " | ".join(self._stderr_buffer[-5:])


def _pi_event_to_runtime_message(event: dict[str, Any]) -> Any | None:
    message = event.get("message")
    if isinstance(message, dict):
        return _to_runtime_message(message)

    assistant_event = event.get("assistantMessageEvent")
    if isinstance(assistant_event, dict):
        delta = assistant_event.get("delta")
        if isinstance(delta, str) and delta:
            return _to_runtime_message({
                "content": [
                    {
                        "type": "text",
                        "text": delta,
                    },
                ],
            })
    return None


def _to_runtime_message(message: dict[str, Any]) -> Any:
    raw_content = message.get("content")
    blocks: list[Any] = []
    if isinstance(raw_content, list):
        for item in raw_content:
            if isinstance(item, dict):
                blocks.append(SimpleNamespace(**item))
    return SimpleNamespace(content=blocks)


def _extract_runtime_api_error(text: str) -> str | None:
    normalized = text.strip()
    if not normalized:
        return None
    if not normalized.lower().startswith("api error:"):
        return None
    return normalized


def _extract_tool_context(args: tuple[Any, ...], kwargs: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    """Extract tool_name and tool_input from can_use_tool callback arguments.

    Expected SDK signature: can_use_tool(tool_name, tool_input, context)
    Falls back to dict-based extraction for forward compatibility.
    Returns ("", {}) only when extraction truly fails; callers MUST treat
    empty tool_name as a security-deny condition.
    """
    # Primary path: positional (tool_name: str, tool_input: dict, context)
    if args and isinstance(args[0], str):
        tool_name = args[0]
        tool_input = args[1] if len(args) > 1 and isinstance(args[1], dict) else {}
        return tool_name, tool_input

    # Alt path: single dict argument
    if args and isinstance(args[0], dict):
        input_data = args[0]
        return str(input_data.get("tool_name", "")), _safe_dict(input_data.get("tool_input"))

    # Alt path: keyword arguments
    if "tool_name" in kwargs:
        return str(kwargs.get("tool_name", "")), _safe_dict(kwargs.get("tool_input"))

    input_data = kwargs.get("input_data", {})
    if isinstance(input_data, dict):
        return str(input_data.get("tool_name", "")), _safe_dict(input_data.get("tool_input"))

    # Extraction failed — log warning
    _logger.warning("_extract_tool_context: unable to extract tool info from args=%r kwargs_keys=%r", args, list(kwargs.keys()))
    return "", {}


def _safe_dict(value: Any) -> dict[str, Any]:
    """Coerce value to dict safely; non-dict values degrade to {} with a warning."""
    if value is None:
        return {}
    if isinstance(value, dict):
        return value
    _logger.warning("_safe_dict: expected dict for tool_input, got %s; degrading to {}", type(value).__name__)
    return {}


def _resolve_env_placeholders(
    env: dict[str, str],
    workstation_id: str,
    task_id: str,
) -> dict[str, str]:
    """Replace {workstationid}, {taskid}, {userid} placeholders in env values.

    Per config-system.md §6.3, these placeholders are resolved before
    passing env to the runtime.
    """
    if not env:
        return {}
    user_id = os.getenv("USER_ID", "")
    resolved: dict[str, str] = {}
    for key, value in env.items():
        resolved[key] = (
            value.replace("{workstationid}", workstation_id)
            .replace("{taskid}", task_id)
            .replace("{userid}", user_id)
        )
    return resolved


def _preset_prompt(preset: str) -> str:
    """Fallback text when use_preset=False and no custom prompt is given."""
    if preset == "claude_code":
        return "You are a reliable software engineering assistant running in LinkWork Agent SDK."
    return ""
