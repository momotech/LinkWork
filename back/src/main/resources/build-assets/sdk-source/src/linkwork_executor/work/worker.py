"""Worker main loop orchestration."""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import logging
import os
import re
import shlex
import shutil
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlparse

from linkwork_agent_sdk.config import ConfigLoader
from linkwork_agent_sdk.constants import (
    BLPOP_TIMEOUT_SECONDS,
    DOC_JOB_PATH,
    DOC_USER_PATH,
    ENV_OSS_MOUNT_REQUIRED,
    ENV_USER_ID,
    ENV_WORKSTATION_ID,
    OSS_INPUT_PATH_TEMPLATE,
    OSS_OUTPUT_REPORT_PATH_TEMPLATE,
    WORKER_LOG_FALLBACK_DIR,
    WORKSPACE_LOGS_PATH,
    WORKSPACE_LOGS_ROOT,
    ZZ_ACTION_FS_CLEANUP,
    ZZ_ACTION_FS_PREPARE,
    build_control_queue_key,
    build_log_stream_key,
    get_task_runtime_idle_timeout_seconds,
)
from linkwork_agent_sdk.engine import AgentEngine
from linkwork_agent_sdk.exceptions import RedisClientError, RuntimeProtocolError, WorkerLifecycleError
from linkwork_agent_sdk.logger import LogEventType, LogRecorder
from linkwork_agent_sdk.redis import RedisClient
from .consumer import DELIVERY_MODE_GIT, FilePathMapping, GitRepoConfig, Task, TaskConsumer
from .agents_guide import ensure_workspace_agents_skill_guidance
from .lifecycle import LifecycleManager
from .workspace import TaskStatus, WorkspaceManager

_logger = logging.getLogger(__name__)

MAX_RUNTIME_PROTOCOL_RETRIES = 1
GIT_COMMAND_TIMEOUT_SECONDS = 300
ZZ_PROCESS_GRACE_SECONDS = 30
MEMORY_LINK_TIMEOUT_SECONDS = 60
TERMINATE_REQUEST_TYPE = "TASK_TERMINATE_REQUEST"
CONTROL_PENDING_MAX = 256
WORKSPACE_ROOT_PATH = "/workspace"
OUTPUT_PATHLIST_MAX_ITEMS = 2000
OUTPUT_IGNORE_DIRS = {".git", ".venv", "venv", "node_modules", "__pycache__", ".claude"}
OUTPUT_IGNORE_ROOT_DIRS = {"logs", "task-logs", "worker-logs"}
OUTPUT_IGNORE_FILES = {"AGENTS.md"}


class Worker:
    """Coordinate consumer, lifecycle and workspace manager."""

    def __init__(self, config_file: str | Path) -> None:
        self._config_file = Path(config_file)
        self._workstation_id = os.getenv(ENV_WORKSTATION_ID, "").strip()
        if not self._workstation_id:
            raise WorkerLifecycleError("WORKSTATION_ID is required")

        self._config_loader = ConfigLoader(self._config_file)
        self._redis_client = RedisClient()
        self._control_redis_client = RedisClient()
        self._worker_logger: LogRecorder | None = None
        self._consumer: TaskConsumer | None = None
        self._lifecycle: LifecycleManager | None = None
        self._workspace: WorkspaceManager | None = None
        self._zz_enabled = False
        self._zz_path = ""
        self._pending_control: dict[str, dict[str, str]] = {}
        self._task_runtime_idle_timeout = get_task_runtime_idle_timeout_seconds()

    async def run_forever(self) -> None:
        try:
            config = self._config_loader.load()
            self._zz_enabled = config.agent.zz_enabled
            self._zz_path = shutil.which("zz") or ""
            if self._zz_enabled and not self._zz_path:
                raise WorkerLifecycleError("agent.zz_enabled=true but zz binary not found in PATH")

            await self._redis_client.connect()
            await self._control_redis_client.connect()

            # Worker lifecycle log goes to local JSONL only (not per-task Redis stream).
            worker_session_id = f"worker-{self._workstation_id}"
            self._worker_logger = LogRecorder(
                output_dir=WORKER_LOG_FALLBACK_DIR,
                session_id=worker_session_id,
            )
            await self._worker_logger.start()

            self._workspace = WorkspaceManager(
                base_dir="/workspace",
                workstation_id=self._workstation_id,
                logger=self._worker_logger,
            )
            await self._workspace.init()

            await self._validate_oss_mount()

            self._consumer = TaskConsumer(
                redis_client=self._redis_client,
                workstation_id=self._workstation_id,
                on_task_completed=self._on_task_completed,
                on_task_failed=self._on_task_failed,
                on_task_aborted=self._on_task_aborted,
            )

            self._lifecycle = LifecycleManager(logger=self._worker_logger)

            while True:
                try:
                    task = await self._consumer.consume_once()
                except RedisClientError as error:
                    _logger.error(
                        "TaskConsumer: Redis BLPOP failed while idle, fail-fast exit",
                        exc_info=True,
                    )
                    raise WorkerLifecycleError("Redis disconnected while waiting for tasks") from error

                if task is None:
                    destroyed = await self._lifecycle.check_and_destroy(self._workstation_id)
                    if destroyed:
                        break
                    continue

                self._lifecycle.update_activity()
                await self._run_task_with_control(task)
                self._lifecycle.update_activity()
        finally:
            # run_forever can be interrupted by signal or lifecycle errors.
            await asyncio.shield(self._shutdown())

    async def _run_task_with_control(self, task: Task) -> None:
        task_future = asyncio.create_task(self._handle_task(task))
        control_future = asyncio.create_task(self._listen_control(task.task_id))

        done, pending = await asyncio.wait(
            {task_future, control_future},
            return_when=asyncio.FIRST_COMPLETED,
        )

        if control_future in done and not task_future.done():
            terminate_payload: dict[str, str] = {}
            try:
                terminate_payload = control_future.result()
            except Exception:
                _logger.warning(
                    "control listener failed for task %s, continue with task execution",
                    task.task_id,
                    exc_info=True,
                )
                await self._await_task_result(task_future)
            else:
                await self._cancel_task_future(task.task_id, task_future)
                await self._on_task_terminated(task, terminate_payload)
        else:
            await self._await_task_result(task_future)

        for pending_future in pending:
            pending_future.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await pending_future

    async def _await_task_result(self, task_future: asyncio.Task[None]) -> None:
        try:
            await task_future
        except asyncio.CancelledError:
            raise
        except Exception:
            _logger.warning("task future ended with unhandled error", exc_info=True)

    async def _cancel_task_future(self, task_id: str, task_future: asyncio.Task[None]) -> None:
        if task_future.done():
            return

        task_future.cancel()
        try:
            await task_future
        except asyncio.CancelledError:
            _logger.info("task %s cancelled by control command", task_id)
        except Exception:
            _logger.warning(
                "task %s raised while cancelling",
                task_id,
                exc_info=True,
            )

    async def _listen_control(self, task_id: str) -> dict[str, str]:
        control_key = build_control_queue_key(self._workstation_id)

        pending = self._pending_control.pop(task_id, None)
        if pending is not None:
            return pending

        while True:
            try:
                result = await self._control_redis_client.blpop(control_key, timeout=BLPOP_TIMEOUT_SECONDS)
            except RedisClientError:
                _logger.warning(
                    "control queue blpop failed, retrying",
                    exc_info=True,
                )
                await asyncio.sleep(1)
                continue

            if result is None:
                continue

            _, payload = result
            normalized = self._normalize_control_payload(payload)
            if normalized is None:
                continue

            message_task_id = normalized["task_id"]
            if message_task_id != task_id:
                self._defer_control_message(message_task_id, normalized)
                _logger.debug(
                    "defer terminate request for task %s while running %s",
                    message_task_id,
                    task_id,
                )
                continue

            return normalized

    def _normalize_control_payload(self, payload: str) -> dict[str, str] | None:
        try:
            message = json.loads(payload)
        except json.JSONDecodeError:
            _logger.warning("invalid control payload ignored: %s", payload)
            return None

        if not isinstance(message, dict):
            _logger.warning("non-object control payload ignored: %s", payload)
            return None

        if str(message.get("type", "")).strip() != TERMINATE_REQUEST_TYPE:
            return None

        message_task_id = str(message.get("task_id", "")).strip()
        if not message_task_id:
            _logger.warning("terminate control payload missing task_id: %s", payload)
            return None

        return {
            "request_id": str(message.get("request_id", "")).strip(),
            "task_id": message_task_id,
            "reason": str(message.get("reason", "user_cancel")).strip() or "user_cancel",
            "operator": str(message.get("operator", "")).strip(),
        }

    def _defer_control_message(self, task_id: str, payload: dict[str, str]) -> None:
        self._pending_control[task_id] = payload
        if len(self._pending_control) <= CONTROL_PENDING_MAX:
            return

        oldest_task_id = next(iter(self._pending_control))
        if oldest_task_id == task_id and len(self._pending_control) > 1:
            oldest_task_id = next(iter(k for k in self._pending_control if k != task_id))
        dropped = self._pending_control.pop(oldest_task_id, None)
        if dropped is not None:
            _logger.warning(
                "control pending cache full, dropped terminate request for task %s",
                oldest_task_id,
            )

    async def _on_task_terminated(self, task: Task, terminate_payload: dict[str, str]) -> None:
        if self._workspace is None:
            raise WorkerLifecycleError("WorkspaceManager is not initialized")

        reason = terminate_payload.get("reason", "user_cancel")
        request_id = terminate_payload.get("request_id", "")

        await self._log_task_event(
            task.task_id,
            LogEventType.TASK_ABORT_ACK,
            {
                "task_id": task.task_id,
                "user_id": task.user_id,
                "request_id": request_id,
                "reason": reason,
            },
        )

        await self._workspace.archive(task.task_id, TaskStatus.ABORTED)
        await self._log_task_event(
            task.task_id,
            LogEventType.WORKSPACE_ARCHIVED,
            {"task_id": task.task_id, "status": "aborted"},
        )

        await self._emit_output_ready_oss_if_needed(
            task=task,
            source_path=self._resolve_task_output_dir(task),
            include_input_link=False,
        )

        await self._log_task_event(
            task.task_id,
            LogEventType.TASK_ABORTED,
            {
                "task_id": task.task_id,
                "user_id": task.user_id,
                "request_id": request_id,
                "reason": reason,
            },
        )

        if self._consumer is not None:
            await self._consumer.mark_aborted(task, reason)

    async def _handle_task(self, task: Task) -> None:
        if self._workspace is None:
            raise WorkerLifecycleError("WorkspaceManager is not initialized")

        await self._log_task_event(
            task.task_id,
            LogEventType.TASK_ASSIGNED,
            {
                "task_id": task.task_id,
                "user_id": task.user_id,
                "task_content": task.content,
            },
        )

        task_workspace = await self._workspace.prepare(task.task_id)
        await self._log_task_event(
            task.task_id,
            LogEventType.WORKSPACE_PREPARED,
            {"task_id": task.task_id, "path": str(task_workspace)},
        )
        await self._ensure_workspace_agents_guide(task.task_id)

        if task.user_id:
            os.environ[ENV_USER_ID] = task.user_id
        if task.task_id:
            os.environ["TASK_ID"] = task.task_id

        prepared_aliases: list[FilePathMapping] = []
        workspace_links_prepared = False
        baseline_snapshot: dict[str, tuple[int, int]] = {}
        try:
            workspace_links_prepared = await self._prepare_workspace_runtime_links(
                task=task,
                task_workspace=task_workspace,
            )
            await self._link_input_files(task=task, task_workspace=task_workspace)
            prepared_aliases = await self._prepare_memory_space_links(
                task=task,
                task_workspace=task_workspace,
            )
            run_git_flow = self._is_git_delivery(task)

            if run_git_flow:
                await self._log_task_event(
                    task.task_id,
                    LogEventType.GIT_PRE_START,
                    {"task_id": task.task_id, "repos": len(task.git_config)},
                )
            try:
                if run_git_flow:
                    await self._run_git_pre_ops(task=task, task_workspace=task_workspace)
            except Exception as error:
                if run_git_flow:
                    await self._log_task_event(
                        task.task_id,
                        LogEventType.GIT_PRE_FAILED,
                        {"task_id": task.task_id, "error_message": str(error)},
                    )
                raise
            if run_git_flow:
                await self._log_task_event(
                    task.task_id,
                    LogEventType.GIT_PRE_DONE,
                    {"task_id": task.task_id, "repos": len(task.git_config)},
                )
            baseline_snapshot = self._snapshot_workspace_state()

            await self._run_task_with_retry(task=task, task_workspace=task_workspace)

            if run_git_flow:
                await self._log_task_event(
                    task.task_id,
                    LogEventType.GIT_POST_START,
                    {"task_id": task.task_id, "repos": len(task.git_config)},
                )
            try:
                if run_git_flow:
                    await self._run_git_post_ops(task=task, task_workspace=task_workspace)
            except Exception as error:
                if run_git_flow:
                    await self._log_task_event(
                        task.task_id,
                        LogEventType.GIT_POST_FAILED,
                        {"task_id": task.task_id, "error_message": str(error)},
                    )
                raise
            if run_git_flow:
                await self._log_task_event(
                    task.task_id,
                    LogEventType.GIT_POST_DONE,
                    {"task_id": task.task_id, "repos": len(task.git_config)},
                )

            if run_git_flow:
                await self._log_output_ready_git(task)
            else:
                await self._emit_output_ready_oss_if_needed(
                    task=task,
                    source_path=self._resolve_task_output_dir(task),
                    include_input_link=False,
                )
            await self._emit_workspace_pathlist(task, baseline_snapshot)

            await self._workspace.archive(task.task_id, TaskStatus.COMPLETED)
            await self._log_task_event(
                task.task_id,
                LogEventType.WORKSPACE_ARCHIVED,
                {"task_id": task.task_id, "status": "completed"},
            )
            await self._log_task_event(
                task.task_id,
                LogEventType.TASK_COMPLETED,
                {"task_id": task.task_id, "user_id": task.user_id},
            )
            if self._consumer is not None:
                await self._consumer.mark_completed(task)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            await self._workspace.archive(task.task_id, TaskStatus.FAILED)
            await self._log_task_event(
                task.task_id,
                LogEventType.WORKSPACE_ARCHIVED,
                {"task_id": task.task_id, "status": "failed"},
            )
            await self._log_task_event(
                task.task_id,
                LogEventType.TASK_FAILED,
                {
                    "task_id": task.task_id,
                    "user_id": task.user_id,
                    "error_message": str(error),
                },
            )
            if self._consumer is not None:
                await self._consumer.mark_failed(task, error)
        finally:
            await self._cleanup_memory_space_links(
                task=task,
                task_workspace=task_workspace,
                prepared_aliases=prepared_aliases,
            )
            await self._cleanup_workspace_runtime_links(
                task=task,
                task_workspace=task_workspace,
                workspace_links_prepared=workspace_links_prepared,
            )

    async def _link_input_files(self, task: Task, task_workspace: Path) -> None:
        source_relative = OSS_INPUT_PATH_TEMPLATE.format(task_id=task.task_id)
        source_path = Path(DOC_JOB_PATH) / source_relative
        if not source_path.exists():
            _logger.info("task %s has no mounted input directory at %s", task.task_id, source_path)
            return

        link_target = task_workspace / "input"
        if link_target.is_symlink() or link_target.is_file():
            link_target.unlink()
        elif link_target.exists() and link_target.is_dir():
            shutil.rmtree(link_target)

        link_target.symlink_to(source_path)
        _logger.info("task %s linked input: %s -> %s", task.task_id, link_target, source_path)

    async def _prepare_workspace_runtime_links(
        self,
        task: Task,
        task_workspace: Path,
    ) -> bool:
        self._ensure_zz_ready_for_file_mappings(task.task_id)
        await self._run_zz_fs_action(
            task_id=task.task_id,
            user_id=task.user_id,
            action=ZZ_ACTION_FS_PREPARE,
            work_dir=task_workspace,
            timeout_seconds=MEMORY_LINK_TIMEOUT_SECONDS,
            error_context="prepare workspace runtime links",
        )
        _logger.info("task %s prepared workspace runtime links", task.task_id)
        return True

    async def _ensure_workspace_agents_guide(self, task_id: str) -> None:
        try:
            await asyncio.to_thread(ensure_workspace_agents_skill_guidance)
        except OSError as error:
            raise WorkerLifecycleError(
                f"prepare workspace AGENTS.md failed for task {task_id}: {error}"
            ) from error

    async def _prepare_memory_space_links(
        self,
        task: Task,
        task_workspace: Path,
    ) -> list[FilePathMapping]:
        mappings = task.file_path_mappings
        if not mappings:
            return []

        self._ensure_zz_ready_for_file_mappings(task.task_id)

        prepared_aliases: list[FilePathMapping] = []
        try:
            for mapping in mappings:
                self._validate_file_path_mapping(mapping)
                if mapping.runtime_path == mapping.real_path:
                    _logger.warning(
                        "task %s skip identity mapping: %s",
                        task.task_id,
                        mapping.runtime_path,
                    )
                    continue
                prepare_alias_cmd = self._build_prepare_alias_command(mapping)
                await self._run_zz_command(
                    task_id=task.task_id,
                    command=prepare_alias_cmd,
                    work_dir=task_workspace,
                    timeout_seconds=MEMORY_LINK_TIMEOUT_SECONDS,
                    error_context=f"prepare memory alias {mapping.runtime_path}",
                )
                prepared_aliases.append(mapping)
        except Exception:
            await self._cleanup_memory_space_links(
                task=task,
                task_workspace=task_workspace,
                prepared_aliases=prepared_aliases,
            )
            raise

        _logger.info(
            "task %s prepared memory aliases: %d",
            task.task_id,
            len(prepared_aliases),
        )
        return prepared_aliases

    async def _cleanup_memory_space_links(
        self,
        task: Task,
        task_workspace: Path,
        prepared_aliases: list[FilePathMapping],
    ) -> None:
        if not prepared_aliases:
            return

        if not self._zz_enabled or not self._zz_path:
            _logger.warning(
                "task %s skip memory cleanup: zz unavailable (enabled=%s, path=%s)",
                task.task_id,
                self._zz_enabled,
                bool(self._zz_path),
            )
            return

        cleanup_failed = False
        for mapping in reversed(prepared_aliases):
            try:
                cleanup_alias_cmd = self._build_cleanup_alias_command(mapping)
                await self._run_zz_command(
                    task_id=task.task_id,
                    command=cleanup_alias_cmd,
                    work_dir=task_workspace,
                    timeout_seconds=MEMORY_LINK_TIMEOUT_SECONDS,
                    error_context=f"cleanup memory alias {mapping.runtime_path}",
                )
            except Exception:
                cleanup_failed = True
                _logger.warning(
                    "task %s cleanup alias failed: %s",
                    task.task_id,
                    mapping.runtime_path,
                    exc_info=True,
                )

        if cleanup_failed:
            _logger.warning("task %s memory link cleanup completed with warnings", task.task_id)
        else:
            _logger.info("task %s memory links cleaned up", task.task_id)

    def _build_prepare_alias_command(self, mapping: FilePathMapping) -> str:
        runtime_path = shlex.quote(mapping.runtime_path)
        real_path = shlex.quote(mapping.real_path)
        return (
            "set -e; "
            f"[ -e {real_path} ] || {{ echo 'missing real file path: {mapping.real_path}' >&2; exit 1; }}; "
            f"runtime_parent=$(dirname {runtime_path}); "
            "mkdir -p \"$runtime_parent\"; "
            f"if [ -L {runtime_path} ]; then rm -f {runtime_path}; "
            f"elif [ -e {runtime_path} ]; then echo 'alias path exists and is not symlink: {mapping.runtime_path}' >&2; exit 1; fi; "
            f"ln -s {real_path} {runtime_path}"
        )

    def _build_cleanup_alias_command(self, mapping: FilePathMapping) -> str:
        runtime_path = shlex.quote(mapping.runtime_path)
        return f"if [ -L {runtime_path} ]; then rm -f {runtime_path}; fi"


    async def _cleanup_workspace_runtime_links(
        self,
        task: Task,
        task_workspace: Path,
        workspace_links_prepared: bool,
    ) -> None:
        if not workspace_links_prepared:
            return

        if not self._zz_enabled or not self._zz_path:
            _logger.warning(
                "task %s skip workspace link cleanup: zz unavailable (enabled=%s, path=%s)",
                task.task_id,
                self._zz_enabled,
                bool(self._zz_path),
            )
            return

        try:
            await self._run_zz_fs_action(
                task_id=task.task_id,
                user_id=task.user_id,
                action=ZZ_ACTION_FS_CLEANUP,
                work_dir=task_workspace,
                timeout_seconds=MEMORY_LINK_TIMEOUT_SECONDS,
                error_context="cleanup workspace runtime links",
            )
            _logger.info("task %s workspace runtime links cleaned up", task.task_id)
        except Exception:
            _logger.warning(
                "task %s cleanup workspace runtime links failed",
                task.task_id,
                exc_info=True,
            )

    def _validate_file_path_mapping(self, mapping: FilePathMapping) -> None:
        runtime_root = self._detect_doc_root(mapping.runtime_path)
        real_root = self._detect_doc_root(mapping.real_path)
        if runtime_root != real_root:
            raise WorkerLifecycleError(
                f"runtime_path and real_path root mismatch: {mapping.runtime_path} -> {mapping.real_path}"
            )

    def _detect_doc_root(self, path: str) -> str:
        if path.startswith(f"{DOC_USER_PATH}/"):
            return DOC_USER_PATH
        if path.startswith(f"{DOC_JOB_PATH}/"):
            return DOC_JOB_PATH
        raise WorkerLifecycleError(f"invalid memory mapping path prefix: {path}")

    def _ensure_zz_ready_for_file_mappings(self, task_id: str) -> None:
        if not self._zz_enabled:
            raise WorkerLifecycleError(
                f"task {task_id} requires zzd runtime actions but agent.zz_enabled=false"
            )
        if not self._zz_path:
            raise WorkerLifecycleError(
                "task requires zzd runtime actions but zz binary not found in PATH"
            )

    async def _archive_workspace_to_oss(
        self,
        task: Task,
        source_path: Path,
        include_input_link: bool,
    ) -> str:
        output_report_path = self._build_output_report_path(task)
        oss_target_path = self._resolve_task_output_dir(task)

        try:
            oss_target_path.parent.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            raise WorkerLifecycleError(
                f"failed to prepare oss output parent {oss_target_path.parent}: {error}"
            ) from error

        if oss_target_path.exists():
            try:
                if any(oss_target_path.iterdir()):
                    return output_report_path
            except OSError as error:
                _logger.info(
                    "task %s uses existing oss output path contract because %s is not readable: %s",
                    task.task_id,
                    oss_target_path,
                    error,
                )
                return output_report_path

        if not source_path.exists():
            _logger.warning(
                "archive source missing for task %s at %s; create empty output directory",
                task.task_id,
                source_path,
            )
            try:
                oss_target_path.mkdir(parents=True, exist_ok=True)
            except OSError as error:
                raise WorkerLifecycleError(
                    f"failed to create empty oss output directory {oss_target_path}: {error}"
                ) from error
            return output_report_path

        with contextlib.suppress(OSError):
            if source_path.resolve() == oss_target_path.resolve():
                oss_target_path.mkdir(parents=True, exist_ok=True)
                return output_report_path

        try:
            ignore = None
            if not include_input_link:
                source_root = source_path.resolve()
                input_entry = source_path / "input"
                skip_input = input_entry.is_symlink()

                def ignore(current_dir: str, names: list[str]) -> set[str]:
                    if not skip_input:
                        return set()
                    if Path(current_dir).resolve() != source_root:
                        return set()
                    if "input" in names:
                        return {"input"}
                    return set()

            await asyncio.to_thread(
                shutil.copytree,
                source_path,
                oss_target_path,
                dirs_exist_ok=True,
                ignore=ignore,
                copy_function=shutil.copy,
            )
        except OSError as error:
            raise WorkerLifecycleError(
                f"failed to archive workspace to oss path {oss_target_path}: {error}"
            ) from error
        return output_report_path

    async def _emit_output_ready_oss_if_needed(
        self,
        task: Task,
        source_path: Path,
        include_input_link: bool,
    ) -> bool:
        if not self._has_task_deliverables(task.task_id):
            copied_files = await self._sync_workspace_deliverables_to_oss(
                task=task,
                source_path=source_path,
                include_input_link=include_input_link,
            )
            if copied_files == 0:
                _logger.info(
                    "task %s has no file deliverables under %s and workspace fallback, skip TASK_OUTPUT_READY",
                    task.task_id,
                    WORKSPACE_LOGS_PATH,
                )
                return False
            _logger.info(
                "task %s recovered %d deliverable file(s) from workspace fallback",
                task.task_id,
                copied_files,
            )

        output_report_path = await self._archive_workspace_to_oss(
            task=task,
            source_path=source_path,
            include_input_link=include_input_link,
        )
        await self._log_output_ready_oss(task, output_report_path)
        return True

    async def _sync_workspace_deliverables_to_oss(
        self,
        task: Task,
        source_path: Path,
        include_input_link: bool,
    ) -> int:
        if not source_path.exists() or not source_path.is_dir():
            return 0

        oss_target_path = self._resolve_task_output_dir(task)

        with contextlib.suppress(OSError):
            if source_path.resolve() == oss_target_path.resolve():
                return 0

        relative_candidates = self._collect_workspace_fallback_candidates(
            source_path=source_path,
            include_input_link=include_input_link,
        )
        if not relative_candidates:
            return 0

        try:
            oss_target_path.mkdir(parents=True, exist_ok=True)
        except OSError as error:
            raise WorkerLifecycleError(
                f"failed to prepare fallback oss output directory {oss_target_path}: {error}"
            ) from error

        def copy_candidates() -> int:
            copied = 0
            for relative in relative_candidates:
                source_file = source_path / relative
                if not source_file.exists() or not source_file.is_file() or source_file.is_symlink():
                    continue
                target_file = oss_target_path / relative
                target_file.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source_file, target_file)
                copied += 1
            return copied

        try:
            return await asyncio.to_thread(copy_candidates)
        except OSError as error:
            raise WorkerLifecycleError(
                f"failed to sync fallback workspace deliverables to {oss_target_path}: {error}"
            ) from error

    def _collect_workspace_fallback_candidates(
        self,
        source_path: Path,
        include_input_link: bool,
    ) -> list[Path]:
        ignored_root_dirs = {".claude", ".git", "__pycache__", ".venv", "node_modules"}
        if not include_input_link:
            ignored_root_dirs.add("input")

        candidates: list[Path] = []
        try:
            for path in source_path.rglob("*"):
                if not path.exists() or not path.is_file() or path.is_symlink():
                    continue

                relative = path.relative_to(source_path)
                if not relative.parts:
                    continue
                if relative.parts[0] in ignored_root_dirs:
                    continue
                if path.name == ".task_meta.json":
                    continue
                if path.name.startswith(".zzd-write-probe-"):
                    continue
                candidates.append(relative)
        except OSError as error:
            _logger.warning(
                "task workspace fallback scan failed at %s: %s",
                source_path,
                error,
            )
            return []
        return candidates

    def _has_task_deliverables(self, task_id: str) -> bool:
        task_output_dir = Path(WORKSPACE_LOGS_PATH)
        if not task_output_dir.exists():
            return False

        try:
            for path in task_output_dir.rglob("*"):
                if path.is_dir():
                    continue
                if path.name.startswith(".zzd-write-probe-"):
                    continue
                return True
            return False
        except OSError as error:
            # Fail-open: if runtime user cannot list NFS path, keep legacy behavior
            # and let _archive_workspace_to_oss return the path contract directly.
            _logger.warning(
                "task %s cannot inspect %s, fallback to output-ready path contract: %s",
                task_id,
                task_output_dir,
                error,
            )
            return True

    async def _validate_oss_mount(self) -> None:
        oss_mount = Path(WORKSPACE_LOGS_ROOT)
        require_mount = os.getenv(ENV_OSS_MOUNT_REQUIRED, "false").strip().lower() in {
            "1",
            "true",
            "yes",
            "on",
        }

        if not oss_mount.exists():
            message = f"oss mount path {oss_mount} does not exist"
            if require_mount:
                raise WorkerLifecycleError(message)
            _logger.warning("%s; continue because OSS_MOUNT_REQUIRED is false", message)
            return

        if not oss_mount.is_dir():
            raise WorkerLifecycleError(f"oss mount path {oss_mount} is not a directory")

        probe_file = oss_mount / f".worker-write-probe-{os.getpid()}"
        try:
            probe_file.write_text("ok", encoding="utf-8")
            probe_file.unlink(missing_ok=True)
        except OSError as error:
            raise WorkerLifecycleError(f"oss mount path {oss_mount} is not writable: {error}") from error

    async def _on_task_completed(self, task: Task) -> None:
        _logger.info("task %s completion callback invoked", task.task_id)

    async def _on_task_failed(self, task: Task, error: Exception) -> None:
        _logger.info("task %s failure callback invoked: %s", task.task_id, error)

    async def _on_task_aborted(self, task: Task, reason: str) -> None:
        _logger.info("task %s aborted callback invoked: %s", task.task_id, reason)

    async def _log_output_ready_git(self, task: Task) -> None:
        repos_payload = []
        for repo_cfg in task.git_config:
            repos_payload.append(
                {
                    "repo": repo_cfg.repo,
                    "task_branch": self._resolve_task_branch(repo_cfg, task.task_id),
                }
            )

        await self._log_task_event(
            task.task_id,
            LogEventType.TASK_OUTPUT_READY,
            {
                "task_id": task.task_id,
                "user_id": task.user_id,
                "output_type": "git",
                "repos": repos_payload,
            },
        )

    async def _log_output_ready_oss(self, task: Task, oss_relative_path: str) -> None:
        await self._log_task_event(
            task.task_id,
            LogEventType.TASK_OUTPUT_READY,
            {
                "task_id": task.task_id,
                "user_id": task.user_id,
                "output_type": "oss",
                "oss_path": oss_relative_path,
            },
        )

    async def _run_git_pre_ops(self, task: Task, task_workspace: Path) -> None:
        if not task.git_config:
            return
        self._ensure_git_workflow_enabled(task.task_id)
        _logger.info("task %s running git pre-ops for %d repos", task.task_id, len(task.git_config))

        for repo_cfg in task.git_config:
            repo_dir = self._resolve_git_repo_dir(task.task_id, repo_cfg)
            task_branch = self._resolve_task_branch(repo_cfg, task.task_id)

            if not repo_dir.exists():
                clone_cmd = f"git clone {shlex.quote(repo_cfg.repo)} {shlex.quote(str(repo_dir))}"
                await self._run_zz_git_command(task.task_id, clone_cmd, task_workspace)

            fetch_cmd = (
                f"git -C {shlex.quote(str(repo_dir))} fetch origin {shlex.quote(repo_cfg.origin_branch)}"
            )
            await self._run_zz_git_command(task.task_id, fetch_cmd, task_workspace)

            remote_ref = f"origin/{repo_cfg.origin_branch}"
            checkout_cmd = (
                f"git -C {shlex.quote(str(repo_dir))} checkout -B {shlex.quote(task_branch)} "
                f"{shlex.quote(remote_ref)}"
            )
            await self._run_zz_git_command(task.task_id, checkout_cmd, task_workspace)

    async def _run_git_post_ops(self, task: Task, task_workspace: Path) -> None:
        if not task.git_config:
            return
        self._ensure_git_workflow_enabled(task.task_id)
        _logger.info("task %s running git post-ops for %d repos", task.task_id, len(task.git_config))

        for repo_cfg in task.git_config:
            repo_dir = self._resolve_git_repo_dir(task.task_id, repo_cfg)
            task_branch = self._resolve_task_branch(repo_cfg, task.task_id)

            status_cmd = f"git -C {shlex.quote(str(repo_dir))} status --porcelain"
            status_output = await self._run_zz_git_command(task.task_id, status_cmd, task_workspace)
            if status_output.strip():
                add_cmd = f"git -C {shlex.quote(str(repo_dir))} add -A"
                await self._run_zz_git_command(task.task_id, add_cmd, task_workspace)

                commit_message = f"task {task.task_id} auto-commit"
                commit_cmd = (
                    f"git -C {shlex.quote(str(repo_dir))} commit -m {shlex.quote(commit_message)}"
                )
                await self._run_zz_git_command(task.task_id, commit_cmd, task_workspace)

            push_cmd = (
                f"git -C {shlex.quote(str(repo_dir))} push -u origin {shlex.quote(task_branch)}"
            )
            await self._run_zz_git_command(task.task_id, push_cmd, task_workspace)

    async def _run_zz_git_command(self, task_id: str, command: str, work_dir: Path) -> str:
        stdout_text = await self._run_zz_command(
            task_id=task_id,
            command=command,
            work_dir=work_dir,
            timeout_seconds=GIT_COMMAND_TIMEOUT_SECONDS,
            error_context="git workflow",
        )
        if stdout_text:
            _logger.info("git workflow command success: %s -> %s", command, stdout_text)
        return stdout_text

    async def _run_zz_fs_action(
        self,
        task_id: str,
        user_id: str,
        action: str,
        work_dir: Path,
        timeout_seconds: int,
        error_context: str,
    ) -> str:
        payload = json.dumps(
            {
                "action": action,
                "task_id": task_id,
                "user_id": user_id,
                "work_dir": str(work_dir),
                "timeout": timeout_seconds,
            },
            ensure_ascii=False,
        ).encode("utf-8")
        return await self._run_zz_payload(
            payload=payload,
            timeout_seconds=timeout_seconds,
            command_label=action,
            error_context=error_context,
        )

    async def _run_zz_command(
        self,
        task_id: str,
        command: str,
        work_dir: Path,
        timeout_seconds: int,
        error_context: str,
    ) -> str:
        zz_path = self._zz_path
        if not zz_path:
            raise WorkerLifecycleError(f"{error_context} requires zz binary in PATH")

        payload = json.dumps(
            {
                "command": command,
                "task_id": task_id,
                "work_dir": str(work_dir),
                "timeout": timeout_seconds,
            },
            ensure_ascii=False,
        ).encode("utf-8")
        return await self._run_zz_payload(
            payload=payload,
            timeout_seconds=timeout_seconds,
            command_label=command,
            error_context=error_context,
        )

    async def _run_zz_payload(
        self,
        payload: bytes,
        timeout_seconds: int,
        command_label: str,
        error_context: str,
    ) -> str:
        zz_path = self._zz_path
        if not zz_path:
            raise WorkerLifecycleError(f"{error_context} requires zz binary in PATH")

        process = await asyncio.create_subprocess_exec(
            zz_path,
            "--stdin",
            "--raw",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )

        process_timeout = timeout_seconds + ZZ_PROCESS_GRACE_SECONDS
        try:
            stdout, stderr = await asyncio.wait_for(
                process.communicate(payload),
                timeout=process_timeout,
            )
        except asyncio.TimeoutError as error:
            process.kill()
            with contextlib.suppress(Exception):
                await process.communicate()
            raise RuntimeProtocolError(
                f"zz process timed out after {process_timeout}s: {command_label}"
            ) from error

        if process.returncode != 0:
            stderr_text = stderr.decode("utf-8", errors="replace").strip()
            if not stderr_text:
                stderr_text = "command execution failed"
            raise RuntimeProtocolError(
                f"{error_context} command failed: {command_label} (exit={process.returncode}): {stderr_text}"
            )

        return stdout.decode("utf-8", errors="replace").strip()

    def _resolve_task_branch(self, repo_cfg: GitRepoConfig, task_id: str) -> str:
        branch = repo_cfg.task_branch.strip()
        if branch:
            return branch
        return f"feat/{task_id}"

    def _derive_repo_dir_name(self, repo_cfg: GitRepoConfig) -> str:
        repo = repo_cfg.repo.strip()
        if not repo:
            raise WorkerLifecycleError("git repo url is empty")

        repo_path = ""
        if "://" in repo:
            parsed = urlparse(repo)
            repo_path = parsed.path
        elif "@" in repo and ":" in repo:
            repo_path = repo.split(":", 1)[1]
        else:
            repo_path = repo

        repo_path = repo_path.strip().strip("/")
        if repo_path.endswith(".git"):
            repo_path = repo_path[:-4]

        segments = [
            self._sanitize_repo_segment(segment)
            for segment in repo_path.split("/")
            if segment and segment not in {".", ".."}
        ]
        if not segments:
            raise WorkerLifecycleError(f"invalid repo path: {repo_cfg.repo}")

        return "__".join(segments)

    def _ensure_git_workflow_enabled(self, task_id: str) -> None:
        if not self._zz_enabled:
            raise WorkerLifecycleError(
                f"task {task_id} includes git_config but agent.zz_enabled=false"
            )
        if not self._zz_path:
            raise WorkerLifecycleError(
                "task includes git_config but zz binary not found in PATH"
            )

    def _sanitize_repo_segment(self, segment: str) -> str:
        sanitized = re.sub(r"[^A-Za-z0-9_.-]+", "-", segment.strip())
        sanitized = sanitized.strip(".-")
        if not sanitized:
            raise WorkerLifecycleError("invalid repository path segment")
        return sanitized

    def _resolve_git_repo_dir(self, task_id: str, repo_cfg: GitRepoConfig) -> Path:
        suffix = self._derive_repo_dir_name(repo_cfg)
        task_suffix = self._sanitize_repo_segment(task_id)[:16]
        return Path("/workspace") / f"git-{task_suffix}-{suffix}"

    def _resolve_task_output_dir(self, task: Task) -> Path:
        return Path(WORKSPACE_LOGS_PATH)

    def _build_output_report_path(self, task: Task) -> str:
        return OSS_OUTPUT_REPORT_PATH_TEMPLATE.format(user_id=task.user_id, task_id=task.task_id)

    async def _run_task_with_retry(self, task: Task, task_workspace: Path) -> None:
        attempt = 0
        while True:
            try:
                await self._run_task_once(task=task, task_workspace=task_workspace)
                return
            except RuntimeProtocolError as error:
                if attempt >= MAX_RUNTIME_PROTOCOL_RETRIES:
                    raise
                attempt += 1
                _logger.warning(
                    "Task %s runtime protocol error, retrying (%s/%s): %s",
                    task.task_id,
                    attempt,
                    MAX_RUNTIME_PROTOCOL_RETRIES,
                    error,
                )
                await self._log_task_event(
                    task.task_id,
                    LogEventType.ERROR,
                    {
                        "task_id": task.task_id,
                        "error_type": type(error).__name__,
                        "error_message": str(error),
                        "retry_attempt": attempt,
                        "max_retries": MAX_RUNTIME_PROTOCOL_RETRIES,
                    },
                )

    async def _run_task_once(self, task: Task, task_workspace: Path) -> None:
        engine_cwd = self._resolve_engine_cwd(task, task_workspace)
        runtime_model_override = self._resolve_runtime_model(task)

        async with AgentEngine(
            config_file=self._config_file,
            task_id=task.task_id,
            workstation_id=self._workstation_id,
            cwd=engine_cwd,
            redis_client=self._redis_client,
            runtime_system_prompt_append=task.system_prompt_append,
            runtime_model_override=runtime_model_override,
        ) as engine:
            stream = engine.run(task.content).__aiter__()
            while True:
                try:
                    await asyncio.wait_for(
                        stream.__anext__(),
                        timeout=self._task_runtime_idle_timeout,
                    )
                except StopAsyncIteration:
                    return
                except asyncio.TimeoutError as error:
                    raise RuntimeProtocolError(
                        "task "
                        f"{task.task_id} runtime stream idle timeout after "
                        f"{self._task_runtime_idle_timeout}s"
                    ) from error

    def _resolve_runtime_model(self, task: Task) -> str | None:
        selected = (task.selected_model or "").strip()
        if selected:
            lowered = selected.lower()
            if "claude" in lowered:
                return selected

            fallback = os.getenv("ANTHROPIC_MODEL", "").strip()
            if fallback:
                _logger.info(
                    "task %s selected_model=%s is not claude-compatible, fallback runtime model=%s",
                    task.task_id,
                    selected,
                    fallback,
                )
                return fallback

            _logger.info(
                "task %s selected_model=%s is not claude-compatible, fallback to config default",
                task.task_id,
                selected,
            )
            return None

        fallback = os.getenv("ANTHROPIC_MODEL", "").strip()
        return fallback or None

    def _is_git_delivery(self, task: Task) -> bool:
        return task.delivery_mode == DELIVERY_MODE_GIT

    def _resolve_engine_cwd(self, task: Task, task_workspace: Path) -> Path:
        return Path(WORKSPACE_ROOT_PATH)

    def _snapshot_workspace_state(self) -> dict[str, tuple[int, int]]:
        root = Path(WORKSPACE_ROOT_PATH)
        if not root.exists():
            return {}
        snapshot: dict[str, tuple[int, int]] = {}
        try:
            for current_dir, dir_names, file_names in os.walk(root, followlinks=True):
                current_path = Path(current_dir)
                try:
                    relative_dir = current_path.relative_to(root)
                except ValueError:
                    continue

                if self._should_skip_relative_path(relative_dir):
                    dir_names[:] = []
                    continue
                dir_names[:] = [
                    name
                    for name in dir_names
                    if not self._should_skip_relative_path(relative_dir / name)
                ]

                for file_name in file_names:
                    relative_path = relative_dir / file_name
                    if self._should_skip_relative_path(relative_path):
                        continue
                    file_path = current_path / file_name
                    try:
                        stat = file_path.stat()
                    except OSError:
                        continue
                    snapshot[relative_path.as_posix()] = (int(stat.st_size), int(stat.st_mtime_ns))
        except OSError as error:
            _logger.warning("snapshot workspace state failed at %s: %s", root, error)
            return {}
        return snapshot

    def _should_skip_relative_path(self, relative_path: Path) -> bool:
        if not relative_path.parts:
            return False
        first_part = relative_path.parts[0]
        if first_part in OUTPUT_IGNORE_ROOT_DIRS:
            return True
        if (
            first_part in {"user", "workstation"}
            and relative_path.name.lower() == "memory.md"
        ):
            return True
        if any(part in OUTPUT_IGNORE_DIRS for part in relative_path.parts):
            return True
        if relative_path.name in OUTPUT_IGNORE_FILES:
            return True
        if relative_path.name.startswith(".task_meta."):
            return True
        return False

    async def _emit_workspace_pathlist(
        self,
        task: Task,
        baseline_snapshot: dict[str, tuple[int, int]],
    ) -> None:
        current_snapshot = self._snapshot_workspace_state()
        changed: list[dict[str, object]] = []
        for relative, meta in current_snapshot.items():
            if baseline_snapshot.get(relative) == meta:
                continue
            category = "intermediate" if relative.startswith("logs/") else "deliverable"
            changed.append(
                {
                    "path": f"{WORKSPACE_ROOT_PATH}/{relative}",
                    "relative_path": relative,
                    "category": category,
                    "action": "upsert",
                    "size": meta[0],
                }
            )

        for relative in baseline_snapshot:
            if relative in current_snapshot:
                continue
            category = "intermediate" if relative.startswith("logs/") else "deliverable"
            changed.append(
                {
                    "path": f"{WORKSPACE_ROOT_PATH}/{relative}",
                    "relative_path": relative,
                    "category": category,
                    "action": "deleted",
                    "size": 0,
                }
            )

        if len(changed) > OUTPUT_PATHLIST_MAX_ITEMS:
            changed = changed[:OUTPUT_PATHLIST_MAX_ITEMS]
        await self._log_task_event(
            task.task_id,
            LogEventType.TASK_OUTPUT_PATHLIST_READY,
            {
                "task_id": task.task_id,
                "user_id": task.user_id,
                "path_list": changed,
                "count": len(changed),
            },
        )

    async def _log_task_event(
        self,
        task_id: str,
        event_type: LogEventType,
        data: dict,
    ) -> None:
        """Write a log event to the per-task Redis stream logs:{ws}:{task_id}."""
        stream_key = build_log_stream_key(self._workstation_id, task_id)
        fields = {
            "event_type": event_type.value,
            "timestamp": datetime.now(UTC).isoformat(),
            "session_id": f"worker-{self._workstation_id}",
            "data": json.dumps(data, ensure_ascii=False),
        }
        try:
            await self._redis_client.xadd(stream_key, fields)
        except Exception:
            _logger.warning(
                "_log_task_event: failed to write %s to stream %s",
                event_type.value,
                stream_key,
                exc_info=True,
            )

    async def _shutdown(self) -> None:
        logger = self._worker_logger
        self._worker_logger = None

        if logger is not None:
            try:
                await logger.stop()
            except Exception:
                _logger.warning("_shutdown: failed to stop worker logger", exc_info=True)

        try:
            await self._redis_client.close()
        except Exception:
            _logger.warning("_shutdown: failed to close redis client", exc_info=True)

        try:
            await self._control_redis_client.close()
        except Exception:
            _logger.warning("_shutdown: failed to close control redis client", exc_info=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="LinkWork Executor worker")
    parser.add_argument(
        "--config",
        default="/opt/agent/config.json",
        help="Path to config file",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    asyncio.run(Worker(args.config).run_forever())
