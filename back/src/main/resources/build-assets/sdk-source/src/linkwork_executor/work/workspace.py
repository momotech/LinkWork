"""Workspace manager for task execution."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import Enum
from pathlib import Path

from linkwork_agent_sdk.logger import LogEventType, LogRecorder


class TaskStatus(str, Enum):
    COMPLETED = "completed"
    FAILED = "failed"
    ABORTED = "aborted"
    INTERRUPTED = "interrupted"


@dataclass(slots=True)
class WorkspacePaths:
    base_dir: Path


class WorkspaceManager:
    """Manage workspace lifecycle under fixed /workspace root."""

    def __init__(
        self,
        base_dir: str,
        workstation_id: str,
        logger: LogRecorder | None = None,
    ) -> None:
        self._paths = WorkspacePaths(base_dir=Path(base_dir))
        self._workstation_id = workstation_id
        self._logger = logger

    @property
    def base_dir(self) -> Path:
        return self._paths.base_dir

    async def init(self) -> None:
        self._paths.base_dir.mkdir(parents=True, exist_ok=True)
        if self._logger is not None:
            await self._logger.record(
                LogEventType.WORKSPACE_INITIALIZED,
                {"workspace_root": str(self._paths.base_dir)},
            )

    async def prepare(self, task_id: str) -> Path:
        self._paths.base_dir.mkdir(parents=True, exist_ok=True)
        _write_meta(
            self._meta_path(task_id),
            {
                "task_id": task_id,
                "workstation_id": self._workstation_id,
                "started_at": _now(),
            },
        )
        return self._paths.base_dir

    async def archive(self, task_id: str, status: TaskStatus) -> Path:
        metadata = _read_meta(self._meta_path(task_id))
        metadata["task_id"] = task_id
        metadata["workstation_id"] = self._workstation_id
        metadata["completed_at"] = _now()
        metadata["status"] = status.value
        _write_meta(self._meta_path(task_id), metadata)
        return self._paths.base_dir

    async def cleanup_residual(self) -> int:
        # Workspace v2 no longer relies on current/work_history directories.
        self._paths.base_dir.mkdir(parents=True, exist_ok=True)
        return 0

    def _meta_path(self, task_id: str) -> Path:
        return self._paths.base_dir / f".task_meta.{task_id}.json"


def _now() -> str:
    return datetime.now(UTC).isoformat()


def _read_meta(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    try:
        raw = path.read_text(encoding="utf-8")
        data = json.loads(raw)
        if isinstance(data, dict):
            return {str(key): str(value) for key, value in data.items()}
    except (OSError, json.JSONDecodeError):
        return {}
    return {}


def _write_meta(path: Path, data: dict[str, str]) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
