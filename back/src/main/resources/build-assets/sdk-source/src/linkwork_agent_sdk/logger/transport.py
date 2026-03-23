"""Transport layer for log delivery."""

from __future__ import annotations

import asyncio
import json
import logging
from abc import ABC, abstractmethod
from datetime import UTC, datetime
from pathlib import Path
from typing import TYPE_CHECKING

from ..constants import LOG_FALLBACK_DIR, build_log_stream_key

if TYPE_CHECKING:
    from ..redis import RedisClient
    from .recorder import LogEvent

_logger = logging.getLogger(__name__)


class LogTransport(ABC):
    """Abstract log transport interface."""

    @abstractmethod
    async def connect(self) -> None:
        """Connect transport backend."""

    @abstractmethod
    async def close(self) -> None:
        """Close transport backend."""

    @abstractmethod
    async def send(self, event: "LogEvent") -> bool:
        """Send one log event."""

    @abstractmethod
    def is_connected(self) -> bool:
        """Whether backend is connected."""


class RedisStreamTransport(LogTransport):
    """Redis XADD transport."""

    def __init__(
        self,
        redis_client: "RedisClient",
        workstation_id: str,
        task_id: str,
        maxlen: int | None = None,
    ) -> None:
        self._redis_client = redis_client
        self._workstation_id = workstation_id
        self._task_id = task_id
        self._maxlen = maxlen
        self._connected = False

    @property
    def stream_key(self) -> str:
        return build_log_stream_key(self._workstation_id, self._task_id)

    async def connect(self) -> None:
        await self._redis_client.connect()
        self._connected = True

    async def close(self) -> None:
        self._connected = False

    async def send(self, event: "LogEvent") -> bool:
        if not self._connected:
            return False
        fields = {
            "event_type": event.event_type.value,
            "timestamp": event.timestamp,
            "session_id": event.session_id,
            "data": json.dumps(event.data, ensure_ascii=False),
        }
        try:
            await self._redis_client.xadd(self.stream_key, fields, maxlen=self._maxlen)
            return True
        except Exception:
            self._connected = False
            return False

    def is_connected(self) -> bool:
        return self._connected


class FileLocalFallback(LogTransport):
    """File-based JSONL fallback transport."""

    def __init__(self, session_id: str, output_dir: str | Path | None = None) -> None:
        self._session_id = session_id
        self._output_dir = Path(output_dir or LOG_FALLBACK_DIR)
        self._log_file: Path | None = None
        self._connected = False

    async def connect(self) -> None:
        try:
            self._output_dir.mkdir(parents=True, exist_ok=True)
            timestamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
            self._log_file = self._output_dir / f"{self._session_id}_{timestamp}.jsonl"
            self._connected = True
        except OSError:
            self._connected = False

    async def close(self) -> None:
        self._connected = False

    async def send(self, event: "LogEvent") -> bool:
        if not self._connected or self._log_file is None:
            return False
        line = event.to_json() + "\n"
        try:
            await asyncio.to_thread(self._write_line, line)
            return True
        except OSError:
            return False

    def _write_line(self, line: str) -> None:
        """Synchronous file write, executed in thread pool to avoid blocking event loop."""
        if self._log_file is None:
            return
        with self._log_file.open("a", encoding="utf-8") as handle:
            handle.write(line)

    def is_connected(self) -> bool:
        return self._connected


class CompositeTransport(LogTransport):
    """Primary transport with fallback transport."""

    def __init__(self, primary: LogTransport, fallback: LogTransport) -> None:
        self._primary = primary
        self._fallback = fallback
        self._connected = False

    async def connect(self) -> None:
        try:
            await self._primary.connect()
        except Exception:
            _logger.warning(
                "CompositeTransport: primary transport connect failed, using fallback",
                exc_info=True,
            )
        await self._fallback.connect()
        self._connected = self._primary.is_connected() or self._fallback.is_connected()

    async def close(self) -> None:
        try:
            await self._primary.close()
        finally:
            await self._fallback.close()
        self._connected = False

    async def send(self, event: "LogEvent") -> bool:
        if self._primary.is_connected() and await self._primary.send(event):
            return True
        if not self._fallback.is_connected():
            await self._fallback.connect()
        return await self._fallback.send(event)

    def is_connected(self) -> bool:
        return self._connected
