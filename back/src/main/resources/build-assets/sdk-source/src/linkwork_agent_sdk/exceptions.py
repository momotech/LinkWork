"""LinkWork Agent SDK exception hierarchy."""

from __future__ import annotations


class LinkWorkAgentSDKError(Exception):
    """Base error for SDK."""


class ConfigError(LinkWorkAgentSDKError):
    """Base config error."""


class ConfigNotFoundError(ConfigError):
    """Config file does not exist."""


class ConfigNullError(ConfigError):
    """Config file content is empty."""


class ConfigParseError(ConfigError):
    """Config file JSON parsing failed."""


class ConfigValidationError(ConfigError):
    """Config model validation failed."""


class ConfigPermissionError(ConfigError):
    """Config file permission denied."""


class RedisClientError(LinkWorkAgentSDKError):
    """Redis client operation failed."""


class SecurityLoadError(LinkWorkAgentSDKError):
    """Security rules loading failed."""


class SecurityExecuteError(LinkWorkAgentSDKError):
    """Security check execution failed."""


class SkillLoadError(LinkWorkAgentSDKError):
    """Skills loading failed."""


class MCPLoadError(LinkWorkAgentSDKError):
    """MCP config loading failed."""


class ConcurrentExecutionError(LinkWorkAgentSDKError):
    """Concurrent run() is not allowed."""


class RuntimeInitError(LinkWorkAgentSDKError):
    """AI runtime initialization failed."""


class RuntimeProtocolError(LinkWorkAgentSDKError):
    """AI runtime returned protocol-invalid response."""


class WorkerLifecycleError(LinkWorkAgentSDKError):
    """Worker lifecycle operation failed."""
