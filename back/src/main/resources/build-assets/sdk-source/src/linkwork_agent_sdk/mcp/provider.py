"""MCP provider for local mcp.json."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..constants import MCP_CONFIG_FILE
from ..exceptions import MCPLoadError


@dataclass(slots=True)
class MCPServerConfig:
    name: str
    type: str = "stdio"
    command: str | None = None
    args: list[str] | None = None
    env: dict[str, str] | None = None
    url: str | None = None
    headers: dict[str, str] | None = None


class MCPProvider:
    """Load MCP server configs and convert to Claude SDK format.

    MCP configuration is baked into the container image at build time.
    At startup:
    - mcp.json missing         → no MCP configured, normal startup
    - mcp.json present, valid  → load servers (empty mcpServers is OK)
    - mcp.json present, broken → raise MCPLoadError to block startup
    """

    def __init__(self, config_file: str | Path | None = None) -> None:
        self._config_file = Path(config_file or MCP_CONFIG_FILE)
        self._servers: dict[str, MCPServerConfig] = {}
        self._global_headers: dict[str, str] = {}

    def load(self) -> dict[str, MCPServerConfig]:
        config_path = self._config_file

        if not config_path.exists() or not config_path.is_file():
            # No mcp.json → role has no MCP configured; normal startup.
            self._servers = {}
            return {}

        try:
            raw_text = config_path.read_text(encoding="utf-8")
        except OSError as error:
            raise MCPLoadError(
                f"MCP config file exists but cannot be read: {config_path}: {error}"
            ) from error

        try:
            raw_data = json.loads(raw_text)
        except json.JSONDecodeError as error:
            raise MCPLoadError(
                f"MCP config file exists but contains invalid JSON: {config_path}: {error}"
            ) from error

        if not isinstance(raw_data, dict):
            raise MCPLoadError(
                f"MCP config must be a JSON object, got {type(raw_data).__name__}"
            )

        raw_global_headers = raw_data.get("globalHeaders")
        if isinstance(raw_global_headers, dict):
            self._global_headers = {str(k): str(v) for k, v in raw_global_headers.items()}

        raw_servers = raw_data.get("mcpServers")
        if raw_servers is None:
            self._servers = {}
            return {}

        if not isinstance(raw_servers, dict):
            raise MCPLoadError("mcpServers must be object")

        parsed: dict[str, MCPServerConfig] = {}
        for name, value in raw_servers.items():
            if not isinstance(value, dict):
                raise MCPLoadError(f"MCP server '{name}' config must be object")

            server_type = str(value.get("type", "stdio"))
            if server_type not in {"stdio", "sse", "http"}:
                raise MCPLoadError(f"MCP server '{name}' has unknown type: {server_type}")

            parsed[name] = MCPServerConfig(
                name=name,
                type=server_type,
                command=_maybe_str(value.get("command")),
                args=_maybe_list_str(value.get("args")),
                env=_maybe_dict_str(value.get("env")),
                url=_maybe_str(value.get("url")),
                headers=_maybe_dict_str(value.get("headers")),
            )

        self._servers = parsed
        return dict(self._servers)

    def get_mcp_servers_config(self) -> dict[str, dict[str, Any]]:
        resolved_globals = _resolve_placeholders(self._global_headers)

        config: dict[str, dict[str, Any]] = {}
        for name, server in self._servers.items():
            if server.type == "stdio":
                payload: dict[str, Any] = {}
                if server.command is not None:
                    payload["command"] = server.command
                if server.args is not None:
                    payload["args"] = server.args
                if server.env is not None:
                    payload["env"] = server.env
                config[name] = payload
                continue

            payload = {
                "type": server.type,
                "url": server.url,
            }
            merged_headers: dict[str, str] = {}
            if resolved_globals:
                merged_headers.update(resolved_globals)
            if server.headers is not None:
                merged_headers.update(server.headers)
            if merged_headers:
                payload["headers"] = merged_headers
            config[name] = payload

        return config

    def get_server(self, name: str) -> MCPServerConfig | None:
        return self._servers.get(name)

    async def probe_servers(self) -> dict[str, dict[str, Any]]:
        """
        Phase 2 预留：探活所有已加载的 MCP Server (http/sse)

        Returns:
            {server_name: {"status": "online"|"offline", "latency_ms": int, "error": str|None}}
        """
        raise NotImplementedError("MCP probe_servers is reserved for Phase 2")

    async def report_health(self, logger: Any) -> None:
        """
        Phase 2 预留：探活后通过 EventLogger 上报 MCP_HEALTH 事件到 Redis Stream
        """
        raise NotImplementedError("MCP report_health is reserved for Phase 2")


def _maybe_str(value: Any) -> str | None:
    if value is None:
        return None
    return str(value)


def _maybe_list_str(value: Any) -> list[str] | None:
    if value is None:
        return None
    if not isinstance(value, list):
        raise MCPLoadError("MCP args must be array")
    return [str(item) for item in value]


def _maybe_dict_str(value: Any) -> dict[str, str] | None:
    if value is None:
        return None
    if not isinstance(value, dict):
        raise MCPLoadError("MCP env/headers must be object")
    return {str(key): str(val) for key, val in value.items()}


_PLACEHOLDER_VARIANTS = [
    ("{taskid}", "TASK_ID"),
    ("{TASKID}", "TASK_ID"),
    ("{TaskId}", "TASK_ID"),
    ("{userid}", "USER_ID"),
    ("{USERID}", "USER_ID"),
    ("{UserId}", "USER_ID"),
]


def _resolve_placeholders(headers: dict[str, str]) -> dict[str, str]:
    """Replace {taskid}/{userid} placeholders with environment variable values."""
    if not headers:
        return {}
    resolved: dict[str, str] = {}
    for key, value in headers.items():
        for placeholder, env_var in _PLACEHOLDER_VARIANTS:
            if placeholder in value:
                value = value.replace(placeholder, os.environ.get(env_var, ""))
        resolved[key] = value
    return resolved
