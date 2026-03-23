"""Config loader for local JSON file."""

from __future__ import annotations

import json
from pathlib import Path

from pydantic import ValidationError

from ..exceptions import (
    ConfigNotFoundError,
    ConfigNullError,
    ConfigParseError,
    ConfigPermissionError,
    ConfigValidationError,
)
from .models import LinkWorkAgentSDKConfig


class ConfigLoader:
    """Load and validate SDK config from local JSON file."""

    def __init__(self, config_file: str | Path) -> None:
        self._config_file = Path(config_file)
        self._config: LinkWorkAgentSDKConfig | None = None

    @property
    def config_file(self) -> Path:
        return self._config_file

    @property
    def config(self) -> LinkWorkAgentSDKConfig:
        if self._config is None:
            raise ConfigValidationError("Config not loaded")
        return self._config

    def load(self) -> LinkWorkAgentSDKConfig:
        """Load JSON config and validate with Pydantic."""
        if not self._config_file.exists():
            raise ConfigNotFoundError(f"Config file not found: {self._config_file}")
        if not self._config_file.is_file():
            raise ConfigNotFoundError(f"Config path is not file: {self._config_file}")

        try:
            content = self._config_file.read_text(encoding="utf-8-sig")
        except PermissionError as error:
            raise ConfigPermissionError(
                f"Config file permission denied: {self._config_file}",
            ) from error
        except OSError as error:
            raise ConfigParseError(f"Config file read failed: {error}") from error

        if not content.strip():
            raise ConfigNullError(f"Config file is empty: {self._config_file}")

        try:
            raw_config = json.loads(content)
        except json.JSONDecodeError as error:
            raise ConfigParseError(
                f"Config JSON parse failed at line {error.lineno}, col {error.colno}: {error.msg}",
            ) from error

        try:
            self._config = LinkWorkAgentSDKConfig.model_validate(raw_config)
        except ValidationError as error:
            details = "; ".join(
                f"{'.'.join(str(part) for part in item['loc'])}: {item['msg']}"
                for item in error.errors()
            )
            raise ConfigValidationError(f"Config validation failed: {details}") from error

        return self._config
