"""Lifecycle manager for idle timeout and self-destroy call."""

from __future__ import annotations

import asyncio
import inspect
import json
import os
import time
import urllib.error
import urllib.request
from typing import Awaitable, Callable

from linkwork_agent_sdk.constants import (
    ENV_POD_NAME,
    ENV_SERVICE_ID,
    ENV_WORKER_DESTROY_API_BASE,
    ENV_WORKER_DESTROY_API_PASSWORD,
    get_idle_timeout_seconds,
)
from linkwork_agent_sdk.exceptions import WorkerLifecycleError
from linkwork_agent_sdk.logger import LogEventType, LogRecorder

DestroyHandler = Callable[[str, int], Awaitable[None] | None]


class LifecycleManager:
    """Manage worker idle timeout and destroy workflow."""

    def __init__(
        self,
        logger: LogRecorder | None = None,
        idle_timeout: int | None = None,
        destroy_handler: DestroyHandler | None = None,
    ) -> None:
        self._logger = logger
        self._idle_timeout = idle_timeout or get_idle_timeout_seconds()
        self._destroy_handler = destroy_handler
        self._last_activity_time = time.time()

    @property
    def idle_timeout(self) -> int:
        return self._idle_timeout

    def update_activity(self) -> None:
        self._last_activity_time = time.time()

    def get_idle_seconds(self) -> int:
        return int(time.time() - self._last_activity_time)

    async def check_and_destroy(self, worker_id: str) -> bool:
        idle_seconds = self.get_idle_seconds()
        if idle_seconds < self._idle_timeout:
            return False

        if self._logger is not None:
            await self._logger.record(
                LogEventType.WORKER_IDLE_TIMEOUT,
                {"idle_seconds": idle_seconds},
            )
            await self._logger.record(
                LogEventType.WORKER_STOP,
                {"reason": "idle_timeout"},
            )

        try:
            await self._destroy(worker_id=worker_id, idle_seconds=idle_seconds)
        except Exception:
            import logging
            logging.getLogger(__name__).warning(
                "scale-down API call failed, worker will exit anyway "
                "(Runner shutdown relies on /shared-keys/shutdown marker)",
                exc_info=True,
            )
        return True

    async def _destroy(self, worker_id: str, idle_seconds: int) -> None:
        if self._destroy_handler is not None:
            result = self._destroy_handler(worker_id, idle_seconds)
            if inspect.isawaitable(result):
                await result
            return

        base_url = os.getenv(ENV_WORKER_DESTROY_API_BASE, "").strip()
        if not base_url:
            return

        password = os.getenv(ENV_WORKER_DESTROY_API_PASSWORD, "").strip()
        if not password:
            raise WorkerLifecycleError(
                "WORKER_DESTROY_API_PASSWORD is required when WORKER_DESTROY_API_BASE is set"
            )

        pod_name = os.getenv(ENV_POD_NAME, "").strip()
        if not pod_name:
            raise WorkerLifecycleError(
                "POD_NAME is required when WORKER_DESTROY_API_BASE is set"
            )

        token = await self._login(base_url, password)

        service_id = os.getenv(ENV_SERVICE_ID, "").strip() or worker_id
        if not service_id:
            raise WorkerLifecycleError("SERVICE_ID/worker_id is empty")
        url = f"{base_url.rstrip('/')}/api/v1/schedule/{service_id}/scale-down"
        payload = json.dumps(
            {
                "podName": pod_name,
                "source": "idle_timeout",
            },
        ).encode("utf-8")
        request = urllib.request.Request(
            url=url,
            data=payload,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {token}",
            },
            method="POST",
        )

        try:
            await asyncio.to_thread(_urlopen, request)
        except urllib.error.URLError as error:
            raise WorkerLifecycleError(f"Scale-down API call failed: {error}") from error

    async def _login(self, base_url: str, password: str) -> str:
        url = f"{base_url.rstrip('/')}/api/v1/auth/login"
        payload = json.dumps({"password": password}).encode("utf-8")
        request = urllib.request.Request(
            url=url,
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )

        try:
            body = await asyncio.to_thread(_urlopen_json, request)
        except urllib.error.URLError as error:
            raise WorkerLifecycleError(f"Login API call failed: {error}") from error
        except (json.JSONDecodeError, ValueError, TypeError) as error:
            raise WorkerLifecycleError(f"Login API response invalid: {error}") from error

        code = body.get("code")
        if code != 0:
            msg = str(body.get("msg", "login failed"))
            raise WorkerLifecycleError(f"Login API rejected: code={code}, msg={msg}")

        data = body.get("data")
        if not isinstance(data, dict):
            raise WorkerLifecycleError("Login response missing data object")

        token = str(data.get("token", "")).strip()
        if not token:
            raise WorkerLifecycleError("Login response missing token")
        return token


def _urlopen(request: urllib.request.Request) -> None:
    with urllib.request.urlopen(request, timeout=10):
        return


def _urlopen_json(request: urllib.request.Request) -> dict:
    with urllib.request.urlopen(request, timeout=10) as response:
        raw = response.read().decode("utf-8")
    body = json.loads(raw)
    if not isinstance(body, dict):
        raise ValueError("response JSON is not an object")
    return body
