"""Security enforcer for tool permission checks."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

from ..constants import SECURITY_RULES_FILE
from ..exceptions import SecurityExecuteError, SecurityLoadError


class SecurityAction(str, Enum):
    ALLOW = "ALLOW"
    DENY = "DENY"


@dataclass(slots=True)
class SecurityRule:
    id: str
    pattern: str
    action: SecurityAction
    message: str = ""
    compiled_pattern: re.Pattern[str] | None = None


@dataclass(slots=True)
class SecurityDecision:
    action: SecurityAction
    rule_id: str | None = None
    message: str = ""


class SecurityEnforcer:
    """Load local security rules and perform allow/deny checks."""

    def __init__(self, rules_file: str | Path | None = None) -> None:
        self._rules_file = Path(rules_file or SECURITY_RULES_FILE)
        self._rules: list[SecurityRule] = []
        self._can_use_tools: list[str] = []
        self._available_mcp_servers: set[str] = set()
        self._normalised_mcp_servers: set[str] = set()

    @property
    def rules(self) -> list[SecurityRule]:
        return list(self._rules)

    @property
    def rule_count(self) -> int:
        return len(self._rules)

    def load(self) -> list[SecurityRule]:
        if not self._rules_file.exists() or not self._rules_file.is_file():
            raise SecurityLoadError(f"Security file not found: {self._rules_file}")

        try:
            content = self._rules_file.read_text(encoding="utf-8")
            raw_data = json.loads(content)
        except json.JSONDecodeError as error:
            raise SecurityLoadError(
                f"Security JSON parse failed: {error}",
            ) from error
        except OSError as error:
            raise SecurityLoadError(f"Security file read failed: {error}") from error

        raw_rules = raw_data.get("rules")
        if not isinstance(raw_rules, list):
            raise SecurityLoadError("Security rules must be an array")

        parsed_rules: list[SecurityRule] = []
        for index, raw_rule in enumerate(raw_rules):
            if not isinstance(raw_rule, dict):
                raise SecurityLoadError(f"Rule at index {index} must be object")
            try:
                rule_id = str(raw_rule["id"])
                pattern = str(raw_rule["pattern"])
                action_str = str(raw_rule["action"])
            except KeyError as error:
                raise SecurityLoadError(
                    f"Rule at index {index} missing field: {error}",
                ) from error

            if action_str not in (SecurityAction.ALLOW.value, SecurityAction.DENY.value):
                raise SecurityLoadError(
                    f"Rule {rule_id} action invalid: {action_str}",
                )

            try:
                compiled = re.compile(pattern)
            except re.error as error:
                raise SecurityLoadError(
                    f"Rule {rule_id} regex invalid: {error}",
                ) from error

            parsed_rules.append(
                SecurityRule(
                    id=rule_id,
                    pattern=pattern,
                    action=SecurityAction(action_str),
                    message=str(raw_rule.get("message", "")),
                    compiled_pattern=compiled,
                ),
            )

        self._rules = parsed_rules
        return list(self._rules)

    def set_can_use_tools(self, tools: list[str]) -> None:
        self._can_use_tools = [item for item in tools if item]

    def set_available_mcp_servers(self, server_names: set[str] | list[str]) -> None:
        self._available_mcp_servers = {name for name in server_names if name}
        # Claude SDK normalises MCP server names: spaces → underscores.
        # Build a lookup that maps *normalised* names back so the capability
        # check succeeds regardless of whether the tool name uses the
        # original or normalised form.
        self._normalised_mcp_servers: set[str] = {
            name.replace(" ", "_") for name in self._available_mcp_servers
        } | self._available_mcp_servers

    def check(self, tool_name: str, tool_input: dict) -> SecurityDecision:
        mcp_check = self._check_mcp_capability(tool_name)
        if mcp_check is not None:
            return mcp_check

        if self._can_use_tools and tool_name not in self._can_use_tools:
            return SecurityDecision(
                action=SecurityAction.DENY,
                rule_id="task_whitelist",
                message=f"Tool '{tool_name}' denied by task can_use_tools",
            )

        payload = json.dumps(tool_input, ensure_ascii=False, sort_keys=True)
        match_target = f"{tool_name}:{payload}"

        allow_rule: SecurityRule | None = None
        deny_rule: SecurityRule | None = None

        for rule in self._rules:
            if rule.compiled_pattern is None:
                raise SecurityExecuteError(f"Rule {rule.id} has no compiled regex")
            if not rule.compiled_pattern.search(match_target):
                continue
            if rule.action == SecurityAction.DENY:
                deny_rule = rule
                break
            if rule.action == SecurityAction.ALLOW and allow_rule is None:
                allow_rule = rule

        if deny_rule is not None:
            return SecurityDecision(
                action=SecurityAction.DENY,
                rule_id=deny_rule.id,
                message=deny_rule.message or "Tool use denied",
            )

        if allow_rule is not None:
            return SecurityDecision(
                action=SecurityAction.ALLOW,
                rule_id=allow_rule.id,
                message="",
            )

        return SecurityDecision(action=SecurityAction.ALLOW)

    def _check_mcp_capability(self, tool_name: str) -> SecurityDecision | None:
        if not tool_name.startswith("mcp__"):
            return None
        if not self._available_mcp_servers:
            return SecurityDecision(
                action=SecurityAction.DENY,
                rule_id="capability_pool",
                message="No MCP servers loaded",
            )

        parts = tool_name.split("__")
        if len(parts) < 3:
            return SecurityDecision(
                action=SecurityAction.DENY,
                rule_id="capability_pool",
                message=f"Invalid MCP tool format: {tool_name}",
            )

        server_name = parts[1]
        if server_name not in self._normalised_mcp_servers:
            return SecurityDecision(
                action=SecurityAction.DENY,
                rule_id="capability_pool",
                message=f"MCP server '{server_name}' not loaded",
            )
        return None
