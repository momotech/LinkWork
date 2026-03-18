#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[build.sh] %s\n' "$*"
}

mkdir -p /workspace /opt/agent /opt/agent/skills

if [[ -n "${MCP_CONFIG:-}" ]]; then
  log "Writing MCP config to /opt/agent/mcp.json"
  printf '%s' "${MCP_CONFIG}" > /opt/agent/mcp.json
fi

if [[ -n "${SKILLS_CONFIG:-}" ]]; then
  log "Writing skills config to /opt/agent/skills/config.json"
  printf '%s' "${SKILLS_CONFIG}" > /opt/agent/skills/config.json
fi

if [[ -n "${GIT_REPOS:-}" ]]; then
  log "Preparing git repositories from GIT_REPOS"
  python3 - <<'PY'
import json
import os
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse, urlunparse

workspace = Path("/workspace")
workspace.mkdir(parents=True, exist_ok=True)

repos_raw = os.getenv("GIT_REPOS", "[]")
token = os.getenv("GIT_TOKEN", "")

try:
    repos = json.loads(repos_raw)
except Exception as exc:
    print(f"[build.sh] Invalid GIT_REPOS JSON: {exc}", file=sys.stderr)
    sys.exit(1)

if not isinstance(repos, list):
    print("[build.sh] GIT_REPOS must be a JSON list", file=sys.stderr)
    sys.exit(1)

def with_token(url: str) -> str:
    if not token or not url.startswith("http"):
        return url
    parsed = urlparse(url)
    if parsed.username or parsed.password:
        return url
    netloc = f"oauth2:{token}@{parsed.netloc}"
    return urlunparse(parsed._replace(netloc=netloc))

for index, item in enumerate(repos):
    if not isinstance(item, dict):
        continue
    url = item.get("url")
    branch = item.get("branch")
    if not url:
        continue
    target = workspace / f"repo-{index}"
    cmd = ["git", "clone", "--depth", "1"]
    if branch:
        cmd.extend(["-b", branch])
    cmd.extend([with_token(url), str(target)])
    print(f"[build.sh] Cloning {url} -> {target}")
    subprocess.check_call(cmd)
PY
fi

chmod -R a+rX /opt/agent || true
log "build.sh completed"
