"""Redis client wrapper for List/Stream operations."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

import redis.asyncio as redis
from redis.exceptions import RedisError

from .constants import get_redis_url
from .exceptions import RedisClientError


class RedisClient:
    """Async Redis wrapper with reconnect-on-demand behavior."""

    def __init__(self, url: str | None = None) -> None:
        self._url = (url or get_redis_url()).strip()
        self._client: redis.Redis | None = None

    @property
    def url(self) -> str:
        return self._url

    async def connect(self) -> None:
        if self._client is not None:
            return
        try:
            self._client = redis.from_url(self._url, decode_responses=True)
            await self._client.ping()
        except Exception as error:
            self._client = None
            raise RedisClientError(f"Redis connect failed: {error}") from error

    async def close(self) -> None:
        if self._client is None:
            return
        await self._client.aclose()
        self._client = None

    async def ping(self) -> bool:
        try:
            await self._call(lambda client: client.ping())
            return True
        except RedisClientError:
            return False

    async def blpop(self, key: str, timeout: int) -> tuple[str, str] | None:
        # Worker idle consume path: fail-fast on Redis disconnect to let supervisor restart.
        result = await self._call(
            lambda client: client.blpop(key, timeout=timeout),
            retry_on_disconnect=False,
        )
        if result is None:
            return None
        queue_key, payload = result
        return str(queue_key), str(payload)

    async def rpush(self, key: str, value: str) -> int:
        result = await self._call(lambda client: client.rpush(key, value))
        return int(result)

    async def xadd(
        self,
        stream_key: str,
        fields: dict[str, Any],
        maxlen: int | None = None,
    ) -> str:
        kwargs: dict[str, Any] = {"fields": fields}
        if maxlen is not None:
            kwargs["maxlen"] = maxlen
            kwargs["approximate"] = True
        result = await self._call(
            lambda client: client.xadd(stream_key, **kwargs),
        )
        return str(result)

    async def xread(
        self,
        streams: dict[str, str],
        block_ms: int | None = None,
        count: int | None = None,
    ) -> list[tuple[str, list[tuple[str, dict[str, Any]]]]]:
        kwargs: dict[str, Any] = {"streams": streams}
        if block_ms is not None:
            kwargs["block"] = block_ms
        if count is not None:
            kwargs["count"] = count
        result = await self._call(
            lambda client: client.xread(**kwargs),
            retry_on_disconnect=False,
        )
        if result is None:
            return []
        return list(result)

    async def _call(
        self,
        operation: Callable[[redis.Redis], Awaitable[Any]],
        retry_on_disconnect: bool = True,
    ) -> Any:
        if self._client is None:
            await self.connect()
        if self._client is None:
            raise RedisClientError("Redis client unavailable")

        try:
            return await operation(self._client)
        except RedisError as error:
            if not retry_on_disconnect:
                raise RedisClientError(
                    f"Redis operation failed (no retry): {error}",
                ) from error

            await self._reset_connection()
            try:
                return await operation(self._client)
            except Exception as retry_error:
                raise RedisClientError(
                    f"Redis operation failed after retry: {retry_error}",
                ) from retry_error
        except Exception as error:
            raise RedisClientError(f"Redis operation failed: {error}") from error

    async def _reset_connection(self) -> None:
        await self.close()
        await self.connect()

    async def __aenter__(self) -> "RedisClient":
        await self.connect()
        return self

    async def __aexit__(self, *_: object) -> None:
        await self.close()
