"""Compatibility shim for legacy `momo_agent_sdk.work` import path."""

from __future__ import annotations

from linkwork_executor.work.worker import Worker

__all__ = ["Worker"]
