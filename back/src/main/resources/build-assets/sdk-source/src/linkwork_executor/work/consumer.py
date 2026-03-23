"""Task consumer for Redis queue."""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from pathlib import PurePosixPath
from typing import Awaitable, Callable

from linkwork_agent_sdk.constants import BLPOP_TIMEOUT_SECONDS, build_task_queue_key
from linkwork_agent_sdk.redis import RedisClient

_logger = logging.getLogger(__name__)

TaskCallback = Callable[["Task"], Awaitable[None] | None]
TaskErrorCallback = Callable[["Task", Exception], Awaitable[None] | None]
TaskAbortCallback = Callable[["Task", str], Awaitable[None] | None]

DELIVERY_MODE_GIT = "git"
DELIVERY_MODE_OSS = "oss"


@dataclass(slots=True)
class GitRepoConfig:
    repo: str
    origin_branch: str
    task_branch: str = ""


@dataclass(slots=True)
class Task:
    task_id: str
    user_id: str
    content: str
    system_prompt_append: str
    delivery_mode: str
    selected_model: str = ""
    role_id: str = ""
    git_config: list[GitRepoConfig] = field(default_factory=list)
    file_path_mappings: list["FilePathMapping"] = field(default_factory=list)


@dataclass(slots=True)
class FilePathMapping:
    runtime_path: str
    real_path: str


class TaskConsumer:
    """Consume task JSON payload from Redis List using BLPOP."""

    def __init__(
        self,
        redis_client: RedisClient,
        workstation_id: str,
        blpop_timeout: int = BLPOP_TIMEOUT_SECONDS,
        on_task_assigned: TaskCallback | None = None,
        on_task_completed: TaskCallback | None = None,
        on_task_failed: TaskErrorCallback | None = None,
        on_task_aborted: TaskAbortCallback | None = None,
    ) -> None:
        self._redis = redis_client
        self._workstation_id = workstation_id
        self._blpop_timeout = blpop_timeout
        self._on_task_assigned = on_task_assigned
        self._on_task_completed = on_task_completed
        self._on_task_failed = on_task_failed
        self._on_task_aborted = on_task_aborted
        self._last_task_time = time.time()

    @property
    def queue_key(self) -> str:
        return build_task_queue_key(self._workstation_id)

    @property
    def last_task_time(self) -> float:
        return self._last_task_time

    async def consume_once(self) -> Task | None:
        result = await self._redis.blpop(self.queue_key, timeout=self._blpop_timeout)

        if result is None:
            return None

        _, payload = result
        try:
            raw_task = json.loads(payload)
            git_config = _parse_git_config(raw_task.get("git_config"))
            delivery_mode = _parse_delivery_mode(raw_task.get("delivery_mode"), git_config)
            file_path_mappings = _parse_file_path_mappings(raw_task.get("file_path_mappings"))

            task_id = str(raw_task["task_id"]).strip()
            user_id = str(raw_task["user_id"]).strip()
            content = str(raw_task["content"]).strip()
            system_prompt_append = str(raw_task["system_prompt_append"]).strip()

            if not task_id:
                raise ValueError("task_id cannot be empty")
            if not user_id:
                raise ValueError("user_id cannot be empty")
            if not content:
                raise ValueError("content cannot be empty")
            if not system_prompt_append:
                raise ValueError("system_prompt_append cannot be empty")

            task = Task(
                task_id=task_id,
                user_id=user_id,
                content=content,
                system_prompt_append=system_prompt_append,
                delivery_mode=delivery_mode,
                selected_model=str(raw_task.get("selected_model", "")).strip(),
                role_id=str(raw_task.get("role_id", "")).strip(),
                git_config=git_config,
                file_path_mappings=file_path_mappings,
            )
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
            _logger.error(
                "TaskConsumer: task payload parse failed, skipping: %s (payload=%s)",
                error,
                payload[:500] if isinstance(payload, str) else payload,
            )
            return None

        self._last_task_time = time.time()
        await _maybe_await(self._on_task_assigned, task)
        return task

    async def mark_completed(self, task: Task) -> None:
        await _maybe_await(self._on_task_completed, task)

    async def mark_failed(self, task: Task, error: Exception) -> None:
        await _maybe_await(self._on_task_failed, task, error)

    async def mark_aborted(self, task: Task, reason: str) -> None:
        await _maybe_await(self._on_task_aborted, task, reason)


def _parse_git_config(raw: object) -> list[GitRepoConfig]:
    if raw is None:
        return []
    if not isinstance(raw, list):
        raise ValueError("git_config must be an array")

    parsed: list[GitRepoConfig] = []
    for item in raw:
        if not isinstance(item, dict):
            raise ValueError("git_config item must be an object")

        repo = str(item.get("repo", "")).strip()
        if not repo:
            raise ValueError("git_config.repo is required")

        origin_branch_raw = item.get("origin_branch")
        if origin_branch_raw is None:
            raise ValueError("git_config.origin_branch is required")

        origin_branch = str(origin_branch_raw).strip()
        if not origin_branch:
            raise ValueError("git_config.origin_branch cannot be empty")

        task_branch = str(item.get("task_branch", "")).strip()
        parsed.append(
            GitRepoConfig(
                repo=repo,
                origin_branch=origin_branch,
                task_branch=task_branch,
            )
        )

    return parsed


def _parse_delivery_mode(raw: object, git_config: list[GitRepoConfig]) -> str:
    mode = str(raw).strip().lower() if raw is not None else ""
    if not mode:
        inferred = DELIVERY_MODE_GIT if git_config else DELIVERY_MODE_OSS
        _logger.warning(
            "task payload missing delivery_mode, fallback to inferred mode: %s (legacy compatibility)",
            inferred,
        )
        mode = inferred

    if mode not in {DELIVERY_MODE_GIT, DELIVERY_MODE_OSS}:
        raise ValueError(f"delivery_mode must be '{DELIVERY_MODE_GIT}' or '{DELIVERY_MODE_OSS}'")

    if mode == DELIVERY_MODE_GIT and not git_config:
        raise ValueError("delivery_mode=git requires non-empty git_config")

    return mode


def _parse_file_path_mappings(raw: object) -> list[FilePathMapping]:
    if raw is None:
        return []
    if not isinstance(raw, list):
        raise ValueError("file_path_mappings must be an array")

    parsed: list[FilePathMapping] = []
    seen_runtime_paths: set[str] = set()
    for item in raw:
        if not isinstance(item, dict):
            raise ValueError("file_path_mappings item must be an object")
        runtime_path = str(item.get("runtime_path", "")).strip()
        real_path = str(item.get("real_path", "")).strip()
        if not runtime_path or not real_path:
            raise ValueError("file_path_mappings.runtime_path and real_path are required")

        runtime_path = _normalize_doc_runtime_path(runtime_path, "runtime_path")
        real_path = _normalize_doc_runtime_path(real_path, "real_path")

        if runtime_path in seen_runtime_paths:
            raise ValueError(f"file_path_mappings has duplicate runtime_path: {runtime_path}")
        seen_runtime_paths.add(runtime_path)

        parsed.append(FilePathMapping(runtime_path=runtime_path, real_path=real_path))

    return parsed


def _normalize_doc_runtime_path(path: str, field_name: str) -> str:
    normalized = PurePosixPath(path)
    if not path.startswith("/"):
        raise ValueError(f"file_path_mappings.{field_name} must be absolute: {path}")
    if ".." in normalized.parts:
        raise ValueError(f"file_path_mappings.{field_name} must not contain '..': {path}")
    normalized_text = str(normalized)
    if normalized_text.startswith("/workspace/user/") or normalized_text.startswith(
        "/workspace/workstation/"
    ):
        return normalized_text
    raise ValueError(
        "file_path_mappings."
        f"{field_name} must start with /workspace/user/ or /workspace/workstation/: {path}"
    )


async def _maybe_await(callback: Callable[..., Awaitable[None] | None] | None, *args: object) -> None:
    if callback is None:
        return
    result = callback(*args)
    if result is None:
        return
    if isinstance(result, Awaitable):
        await result
