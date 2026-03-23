"""LinkWork Agent SDK constants."""

from __future__ import annotations

import os

SKILLS_DIR = "/opt/agent/skills/"
MCP_CONFIG_FILE = "/opt/agent/mcp.json"
SECURITY_RULES_FILE = "/opt/agent/security.json"
LOG_FALLBACK_DIR = "/workspace/task-logs/"
WORKER_LOG_FALLBACK_DIR = "/workspace/worker-logs/"

REDIS_URL_DEFAULT = "redis://redis:6379"
BLPOP_TIMEOUT_SECONDS = 5
IDLE_TIMEOUT_SECONDS = 600
TASK_RUNTIME_IDLE_TIMEOUT_SECONDS = 600
LOG_RETENTION_DAYS = 7
SECURITY_CHECK_TIMEOUT_SECONDS = 2

TASK_QUEUE_KEY_TEMPLATE = "workstation:{workstation_id}:tasks"
CONTROL_QUEUE_KEY_TEMPLATE = "workstation:{workstation_id}:control"
LOG_STREAM_KEY_TEMPLATE = "logs:{workstation_id}:{task_id}"

WORKSPACE_LOGS_ROOT = "/workspace/logs"
WORKSPACE_USER_ROOT = "/workspace/user"
WORKSPACE_WORKSTATION_ROOT = "/workspace/workstation"
OSS_INPUT_PATH_TEMPLATE = "tasks/{task_id}/input"
OSS_OUTPUT_REPORT_PATH_TEMPLATE = "logs/{user_id}/{task_id}"

WORKSPACE_LOGS_PATH = "/workspace/logs"
DOC_ROOT_PATH = "/workspace"
DOC_USER_PATH = "/workspace/user"
DOC_JOB_PATH = "/workspace/workstation"

ZZ_ACTION_FS_PREPARE = "fs_prepare"
ZZ_ACTION_FS_CLEANUP = "fs_cleanup"

ENV_WORKSTATION_ID = "WORKSTATION_ID"
ENV_TASK_ID = "TASK_ID"
ENV_USER_ID = "USER_ID"
ENV_REDIS_URL = "REDIS_URL"
ENV_IDLE_TIMEOUT = "IDLE_TIMEOUT"
ENV_TASK_RUNTIME_IDLE_TIMEOUT = "TASK_RUNTIME_IDLE_TIMEOUT"
ENV_WORKER_DESTROY_API_BASE = "WORKER_DESTROY_API_BASE"
ENV_WORKER_DESTROY_API_PASSWORD = "WORKER_DESTROY_API_PASSWORD"
ENV_POD_NAME = "POD_NAME"
ENV_SERVICE_ID = "SERVICE_ID"
ENV_OSS_MOUNT_REQUIRED = "OSS_MOUNT_REQUIRED"


def get_redis_url() -> str:
    """Get Redis URL from env with default fallback."""
    value = os.getenv(ENV_REDIS_URL, "").strip()
    return value or REDIS_URL_DEFAULT


def get_idle_timeout_seconds() -> int:
    """Get idle timeout seconds from env with safe default fallback."""
    raw = os.getenv(ENV_IDLE_TIMEOUT, "").strip()
    if not raw:
        return IDLE_TIMEOUT_SECONDS
    try:
        parsed = int(raw)
    except ValueError:
        return IDLE_TIMEOUT_SECONDS
    if parsed <= 0:
        return IDLE_TIMEOUT_SECONDS
    return parsed


def get_task_runtime_idle_timeout_seconds() -> int:
    """Get per-task runtime idle timeout from env with safe default fallback."""
    raw = os.getenv(ENV_TASK_RUNTIME_IDLE_TIMEOUT, "").strip()
    if not raw:
        return TASK_RUNTIME_IDLE_TIMEOUT_SECONDS
    try:
        parsed = int(raw)
    except ValueError:
        return TASK_RUNTIME_IDLE_TIMEOUT_SECONDS
    if parsed <= 0:
        return TASK_RUNTIME_IDLE_TIMEOUT_SECONDS
    return parsed


def build_task_queue_key(workstation_id: str) -> str:
    """Build Redis task queue key."""
    return TASK_QUEUE_KEY_TEMPLATE.format(workstation_id=workstation_id)


def build_control_queue_key(workstation_id: str) -> str:
    """Build Redis control queue key."""
    return CONTROL_QUEUE_KEY_TEMPLATE.format(workstation_id=workstation_id)


def build_log_stream_key(workstation_id: str, task_id: str) -> str:
    """Build Redis log stream key."""
    return LOG_STREAM_KEY_TEMPLATE.format(
        workstation_id=workstation_id,
        task_id=task_id,
    )
