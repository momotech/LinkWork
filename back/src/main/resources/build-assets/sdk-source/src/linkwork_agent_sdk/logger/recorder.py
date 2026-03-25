"""Log recorder and event model."""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import Enum
from pathlib import Path
from typing import Any

from ..constants import LOG_FALLBACK_DIR
from .transport import FileLocalFallback, LogTransport

_logger = logging.getLogger(__name__)


class LogEventType(str, Enum):
    SESSION_START = "SESSION_START"
    SESSION_END = "SESSION_END"
    TASK_ASSIGNED = "TASK_ASSIGNED"
    TASK_COMPLETED = "TASK_COMPLETED"
    TASK_FAILED = "TASK_FAILED"
    TASK_ABORTED = "TASK_ABORTED"
    TASK_ABORT_ACK = "TASK_ABORT_ACK"
    TASK_OUTPUT_READY = "TASK_OUTPUT_READY"
    TASK_OUTPUT_PATHLIST_READY = "TASK_OUTPUT_PATHLIST_READY"
    TOOL_CALL = "TOOL_CALL"
    TOOL_RESULT = "TOOL_RESULT"
    TOOL_ERROR = "TOOL_ERROR"
    SECURITY_ALLOW = "SECURITY_ALLOW"
    SECURITY_DENY = "SECURITY_DENY"
    WATERMARK_ATTACHED = "WATERMARK_ATTACHED"
    CONFIG_LOADED = "CONFIG_LOADED"
    SECURITY_LOADED = "SECURITY_LOADED"
    SKILLS_LOADED = "SKILLS_LOADED"
    SKILL_SELECTED = "SKILL_SELECTED"
    SKILL_REFERENCED = "SKILL_REFERENCED"
    SKILL_USAGE_SUMMARY = "SKILL_USAGE_SUMMARY"
    MCP_LOADED = "MCP_LOADED"
    MCP_CONNECTED = "MCP_CONNECTED"        # 预留：MCP Server 连接成功
    MCP_DISCONNECTED = "MCP_DISCONNECTED"  # 预留：MCP Server 连接断开
    MCP_HEALTH = "MCP_HEALTH"              # 预留：MCP 健康状态汇报
    MCP_ERROR = "MCP_ERROR"                # 预留：MCP 调用异常
    ERROR = "ERROR"
    THINKING = "THINKING"
    ASSISTANT_TEXT = "ASSISTANT_TEXT"
    WORKER_IDLE_TIMEOUT = "WORKER_IDLE_TIMEOUT"
    WORKER_STOP = "WORKER_STOP"
    WORKSPACE_INITIALIZED = "WORKSPACE_INITIALIZED"
    WORKSPACE_PREPARED = "WORKSPACE_PREPARED"
    WORKSPACE_ARCHIVED = "WORKSPACE_ARCHIVED"
    WORKSPACE_CLEANED = "WORKSPACE_CLEANED"
    GIT_PRE_START = "GIT_PRE_START"
    GIT_PRE_DONE = "GIT_PRE_DONE"
    GIT_PRE_FAILED = "GIT_PRE_FAILED"
    GIT_POST_START = "GIT_POST_START"
    GIT_POST_DONE = "GIT_POST_DONE"
    GIT_POST_FAILED = "GIT_POST_FAILED"
    ZZ_RESULT = "ZZ_RESULT"


@dataclass(slots=True)
class LogEvent:
    event_type: LogEventType
    session_id: str
    data: dict[str, Any] = field(default_factory=dict)
    timestamp: str = field(
        default_factory=lambda: datetime.now(UTC).isoformat(),
    )

    def to_dict(self) -> dict[str, Any]:
        return {
            "event_type": self.event_type.value,
            "timestamp": self.timestamp,
            "session_id": self.session_id,
            "data": self.data,
        }

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False)


class LogRecorder:
    """Record events and send through transport; never block main flow."""

    def __init__(
        self,
        output_dir: str | Path,
        session_id: str,
        transport: LogTransport | None = None,
    ) -> None:
        self._output_dir = Path(output_dir)
        self._session_id = session_id
        self._transport = transport or FileLocalFallback(
            session_id=session_id,
            output_dir=LOG_FALLBACK_DIR,
        )
        self._started_at: float | None = None

    @property
    def session_id(self) -> str:
        return self._session_id

    async def start(self) -> None:
        self._started_at = time.monotonic()
        try:
            await self._transport.connect()
        finally:
            await self.record(LogEventType.SESSION_START, {})

    async def stop(self) -> None:
        duration_ms = None
        if self._started_at is not None:
            duration_ms = int((time.monotonic() - self._started_at) * 1000)
        await self.record(
            LogEventType.SESSION_END,
            {
                "duration_ms": duration_ms,
            },
        )
        try:
            await self._transport.close()
        except Exception:
            _logger.warning("LogRecorder.stop: transport close failed", exc_info=True)

    async def record(
        self,
        event_type: LogEventType,
        data: dict[str, Any] | None = None,
    ) -> None:
        payload = _truncate_payload(data or {})
        event = LogEvent(
            event_type=event_type,
            session_id=self._session_id,
            data=payload,
        )
        try:
            await self._transport.send(event)
        except Exception:
            _logger.warning(
                "LogRecorder.record: transport send failed for event %s",
                event_type.value,
                exc_info=True,
            )
            return


def _truncate_payload(data: dict[str, Any], max_chars: int = 20_000) -> dict[str, Any]:
    serialized = json.dumps(data, ensure_ascii=False)
    if len(serialized) <= max_chars:
        return data
    return {
        "_truncated": serialized[:max_chars],
        "_original_size": len(serialized),
    }
