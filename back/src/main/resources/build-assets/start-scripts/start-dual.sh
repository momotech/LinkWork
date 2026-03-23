#!/bin/bash
# =============================================================================
# AI 员工双容器入口脚本（生产版本）
#
# 职责：SSH 密钥管理 → 启动 zzd (sandbox 模式) → AI 员工 Worker
# 参考：docs/docker.md §4.2
#
# 必填环境变量：
#   WORKSTATION_ID  - 工位 ID
#   REDIS_URL       - Redis 连接地址
#
# 可选环境变量：
#   ZZD_RUNNER_HOST      - Runner SSH 主机 (默认: localhost，同 Pod)
#   ZZD_ENABLE_APPROVAL  - 启用审批队列
#   ZZD_REDIS_ADDR       - 审批队列 Redis 地址
#   LOG_LEVEL                 - 日志级别 (默认: INFO)
#   ENABLE_NETWORK_FIREWALL   - iptables 网络白名单开关 (true/1=启用, 默认关闭)
# =============================================================================
set -e

readonly CONFIG_FILE="/opt/agent/config.json"
readonly SOCKET_PATH="/var/run/zzd/zzd.sock"
readonly SOCKET_TIMEOUT=20
readonly KEY_DIR="/etc/zzd/keys"
readonly SHARED_KEY_DIR="/shared-keys"
readonly RUNNER_HOST="${ZZD_RUNNER_HOST:-localhost}"
readonly RUNNER_SSH_TIMEOUT=60
readonly WORKSPACE_GROUP="${WORKSPACE_GROUP:-workspace}"
readonly WORKSPACE_GID="${WORKSPACE_GID:-2000}"
WORKSPACE_EFFECTIVE_GROUP="${WORKSPACE_GROUP}"
readonly ZZD_FORCE_GIT_LOCAL_ROUTE="${ZZD_FORCE_GIT_LOCAL_ROUTE:-true}"
readonly RUNTIME_GIT_API_FALLBACK_URL="${RUNTIME_GIT_API_FALLBACK_URL:-http://tjwq-robot-web-002.tjwq.prod.momo.momo.com:18081}"

# =============================================================================
# 日志
# =============================================================================
log_info()  { echo "[INFO]  $(date '+%H:%M:%S') $*"; }
log_error() { echo "[ERROR] $(date '+%H:%M:%S') $*" >&2; }
log_warn()  { echo "[WARN]  $(date '+%H:%M:%S') $*"; }

load_env_file() {
    local env_file="${ENV_FILE:-/opt/agent/.env}"
    if [[ ! -f "${env_file}" && -f "/workspace/.env" ]]; then
        env_file="/workspace/.env"
    fi
    if [[ ! -f "${env_file}" ]]; then
        log_warn "未找到 .env 文件（ENV_FILE=${ENV_FILE:-未设置}），跳过加载"
        return 0
    fi

    while IFS= read -r line || [[ -n "${line}" ]]; do
        line="${line%$'\r'}"
        [[ -z "${line}" || "${line}" == \#* ]] && continue
        [[ "${line}" != *=* ]] && continue

        local key="${line%%=*}"
        local value="${line#*=}"
        key="${key//[[:space:]]/}"
        [[ -z "${key}" ]] && continue

        if [[ "${value}" == \"*\" && "${value}" == *\" ]]; then
            value="${value:1:${#value}-2}"
        elif [[ "${value}" == \'*\' && "${value}" == *\' ]]; then
            value="${value:1:${#value}-2}"
        fi
        export "${key}=${value}"
    done < "${env_file}"

    log_info "已加载 .env 文件: ${env_file}"
}

configure_runtime_git_token() {
    if [[ -z "${ZZD_API_SERVER_URL:-}" && -n "${API_BASE_URL:-}" ]]; then
        export ZZD_API_SERVER_URL="${API_BASE_URL}"
        log_info "ZZD_API_SERVER_URL 未设置，回退使用 API_BASE_URL"
    fi

    if [[ "${ZZD_API_SERVER_URL:-}" == *"api-gateway.ai-worker.svc"* ]]; then
        log_warn "检测到不可达 API 地址 (${ZZD_API_SERVER_URL})，强制改写为 ${RUNTIME_GIT_API_FALLBACK_URL}"
        export ZZD_API_SERVER_URL="${RUNTIME_GIT_API_FALLBACK_URL}"
    fi

    if [[ -z "${ZZD_ENABLE_GIT_TOKEN:-}" && -n "${ZZD_API_SERVER_URL:-}" ]]; then
        export ZZD_ENABLE_GIT_TOKEN="true"
    fi

    local token_set="false"
    if [[ -n "${ZZD_API_SERVER_TOKEN:-}" ]]; then
        token_set="true"
    fi
    log_info "runtime git token: enabled=${ZZD_ENABLE_GIT_TOKEN:-false}, api=${ZZD_API_SERVER_URL:-未设置}, token_set=${token_set}"
}

write_git_local_routing_config() {
    mkdir -p /etc/zzd
    local routing_file="/etc/zzd/routing.yaml"
    cat > "${routing_file}" <<'YAML'
rules:
  - match: ["git"]
    target: local
    reason: "git commands stay in agent container for runtime token injection"
YAML
    chmod 0400 "${routing_file}"
    chown root:root "${routing_file}"
    log_info "zzd 路由已写入: ${routing_file} (git -> local)"
}

resolve_python_bin() {
    local configured="${PYTHON_BIN:-/usr/bin/python3.12}"
    if [[ "${configured}" == */* ]]; then
        if [[ -x "${configured}" ]]; then
            echo "${configured}"
            return 0
        fi
    elif command -v "${configured}" >/dev/null 2>&1; then
        command -v "${configured}"
        return 0
    fi

    if command -v python3.12 >/dev/null 2>&1; then
        command -v python3.12
        return 0
    fi
    if command -v python3 >/dev/null 2>&1; then
        command -v python3
        return 0
    fi

    log_error "未找到可用 Python 解释器（尝试 PYTHON_BIN/python3.12/python3）"
    return 1
}

# =============================================================================
# workspace 共享权限 (保持 agent/runner UID 隔离，通过共享 GID 协作)
# =============================================================================
setup_workspace_group_permissions() {
    local resolved_group="${WORKSPACE_GROUP}"

    if getent group "${WORKSPACE_GID}" >/dev/null 2>&1; then
        resolved_group=$(getent group "${WORKSPACE_GID}" | cut -d: -f1)
    elif ! getent group "${WORKSPACE_GROUP}" >/dev/null 2>&1; then
        groupadd -g "${WORKSPACE_GID}" "${WORKSPACE_GROUP}" || {
            log_error "创建 workspace 协作组失败 (${WORKSPACE_GROUP}:${WORKSPACE_GID})"
            return 1
        }
    fi

    usermod -aG "${resolved_group}" agent || {
        log_error "将 agent 加入 workspace 协作组失败 (${resolved_group})"
        return 1
    }

    for dir in /workspace /workspace/logs /workspace/user /workspace/workstation /workspace/task-logs /workspace/worker-logs; do
        mkdir -p "${dir}"
        chgrp -R "${resolved_group}" "${dir}"
        chmod -R g+rwX "${dir}"
        find "${dir}" -type d -exec chmod g+s {} +
        chmod 2770 "${dir}"
    done
    WORKSPACE_EFFECTIVE_GROUP="${resolved_group}"
    export WORKSPACE_EFFECTIVE_GROUP

    log_info "workspace 权限已对齐 (group=${resolved_group}, dirs=workspace/logs/user/workstation/task-logs/worker-logs, umask=0002)"
    log_info "agent groups: $(id -nG agent 2>/dev/null || echo 'unknown')"
}

# =============================================================================
# 1. 启动前校验
# =============================================================================
log_info "================================================"
log_info " AI 员工 - 双容器模式 (ZZD_MODE=sandbox)"
log_info " WORKSTATION_ID:      ${WORKSTATION_ID:-未设置}"
log_info " REDIS_URL:           ${REDIS_URL:-未设置}"
log_info " ZZD_RUNNER_HOST:     ${RUNNER_HOST}"
log_info " ZZD_ENABLE_APPROVAL: ${ZZD_ENABLE_APPROVAL:-未设置}"
log_info " ZZD_REDIS_ADDR:      ${ZZD_REDIS_ADDR:-未设置}"
log_info " ZZD_FORCE_GIT_LOCAL_ROUTE: ${ZZD_FORCE_GIT_LOCAL_ROUTE}"
log_info " CONFIG_FILE:         ${CONFIG_FILE}"
log_info "================================================"
log_info "Git wrapper 已禁用；runtime workspace 策略由 zzd fs_prepare/fs_cleanup 管控"

if [ -z "$WORKSTATION_ID" ]; then
    log_error "WORKSTATION_ID 未设置"
    exit 1
fi

for cmd in zz zzd gen-key encrypt-key ssh-keygen ssh-keyscan; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        log_error "必需命令未找到: $cmd"
        exit 1
    fi
done

load_env_file
configure_runtime_git_token
if [[ "${ZZD_FORCE_GIT_LOCAL_ROUTE}" == "true" ]]; then
    write_git_local_routing_config
else
    log_warn "已关闭 Git 本地路由强制（ZZD_FORCE_GIT_LOCAL_ROUTE=${ZZD_FORCE_GIT_LOCAL_ROUTE}）"
fi

if [ ! -f "$CONFIG_FILE" ]; then
    log_error "配置文件不存在: $CONFIG_FILE"
    exit 1
fi

# =============================================================================
# 1.5. 加载网络隔离防火墙（build.sh 生成的 iptables 规则，默认关闭）
# =============================================================================
readonly FIREWALL_SCRIPT="/opt/agent/setup-firewall.sh"
if [[ "${ENABLE_NETWORK_FIREWALL}" == "true" || "${ENABLE_NETWORK_FIREWALL}" == "1" ]]; then
    if [[ -x "${FIREWALL_SCRIPT}" ]]; then
        log_info "加载网络隔离防火墙..."
        if bash "${FIREWALL_SCRIPT}"; then
            log_info "iptables 网络白名单已生效"
        else
            log_warn "iptables 加载失败（可能缺少 NET_ADMIN），继续启动"
        fi
    else
        log_warn "ENABLE_NETWORK_FIREWALL 已启用但防火墙脚本不存在: ${FIREWALL_SCRIPT}"
    fi
else
    log_info "ENABLE_NETWORK_FIREWALL 未启用，跳过网络隔离 (agent 可访问全部网络)"
fi

# =============================================================================
# 2. SSH 密钥管理
# =============================================================================
log_info "SSH 密钥管理..."
mkdir -p "$KEY_DIR"

# 生成 ZZD_MASTER_KEY（不全局 export，仅在需要的命令前内联注入）
log_info "gen-key → 生成 master key..."
GEN_KEY_OUTPUT=$(gen-key)
MASTER_KEY=$(echo "$GEN_KEY_OUTPUT" | sed -n '2p')
log_info "ZZD_MASTER_KEY 已生成 (长度=${#MASTER_KEY})"

# 生成 SSH 密钥对
log_info "生成 SSH ed25519 密钥对..."
rm -f /tmp/zzd_id_ed25519 /tmp/zzd_id_ed25519.pub
ssh-keygen -t ed25519 -f /tmp/zzd_id_ed25519 -N "" -q

# 加密密钥（内联注入 MASTER_KEY，不污染全局环境）
log_info "encrypt-key → momobot_key.enc..."
ZZD_MASTER_KEY="$MASTER_KEY" encrypt-key -input /tmp/zzd_id_ed25519 -output "$KEY_DIR/momobot_key.enc"

log_info "复制 momobot_key.enc → root_key.enc (相同密钥)"
cp "$KEY_DIR/momobot_key.enc" "$KEY_DIR/root_key.enc"

# 导出公钥到共享卷供 Runner 读取
mkdir -p "$SHARED_KEY_DIR"
cp /tmp/zzd_id_ed25519.pub "$SHARED_KEY_DIR/zzd_pubkey.pub"
chmod 644 "$SHARED_KEY_DIR/zzd_pubkey.pub"
log_info "公钥已导出: $SHARED_KEY_DIR/zzd_pubkey.pub"

# 清理明文私钥
rm -f /tmp/zzd_id_ed25519

# =============================================================================
# 3. 等待 Runner SSH 就绪
# =============================================================================
log_info "等待 Runner SSH 就绪 ($RUNNER_HOST)..."
WAIT=0
while ! ssh-keyscan -T 2 "$RUNNER_HOST" 2>/dev/null | grep -q .; do
    WAIT=$((WAIT + 2))
    if [ $WAIT -ge $RUNNER_SSH_TIMEOUT ]; then
        log_error "Runner SSH 未就绪 (${RUNNER_SSH_TIMEOUT}s 超时)"
        exit 1
    fi
    log_info "等待 Runner... (${WAIT}s)"
    sleep 2
done

# 获取 Runner host key
log_info "ssh-keyscan → known_hosts..."
ssh-keyscan -H "$RUNNER_HOST" >> /etc/zzd/known_hosts 2>/dev/null

# agent 用户也需要 known_hosts（调试用）
mkdir -p /root/.ssh /home/agent/.ssh
cp /etc/zzd/known_hosts /root/.ssh/known_hosts
cp /etc/zzd/known_hosts /home/agent/.ssh/known_hosts
chown -R agent:agent /home/agent/.ssh
log_info "known_hosts 已更新"

# =============================================================================
# 3.5. 对齐 workspace 协作权限（双容器共享卷写入）
# =============================================================================
setup_workspace_group_permissions

# =============================================================================
# 4. 启动 zzd (sandbox 模式, root 运行, 内联注入 MASTER_KEY)
# =============================================================================
log_info "启动 zzd (sandbox 模式)..."
ZZD_MODE=sandbox ZZD_MASTER_KEY="$MASTER_KEY" zzd &
ZZD_PID=$!

# MASTER_KEY 已传递给 zzd 进程，从 shell 变量中清除
unset MASTER_KEY

# 等待 socket 就绪（sleep 1 × SOCKET_TIMEOUT 次 = 实际超时秒数）
WAIT=0
while [ ! -S "$SOCKET_PATH" ] && [ $WAIT -lt $SOCKET_TIMEOUT ]; do
    sleep 1
    WAIT=$((WAIT + 1))
done

if [ ! -S "$SOCKET_PATH" ]; then
    log_error "zzd socket 未就绪 (${SOCKET_TIMEOUT}s 超时)"
    kill $ZZD_PID 2>/dev/null
    exit 1
fi

# socket 权限：仅 root + agent 组可读写 (docs/zzd/zzd.md §安全机制)
chown root:agent /var/run/zzd
chmod 0750 /var/run/zzd
chown root:agent "$SOCKET_PATH"
chmod 0660 "$SOCKET_PATH"
log_info "zzd 就绪 (pid=$ZZD_PID, socket=$SOCKET_PATH, 0660 root:agent)"

# =============================================================================
# 5. 启动 AI 员工 (agent 用户, 白名单环境变量)
# =============================================================================
log_info "启动 AI 员工 (user=agent)..."
log_info "================================================"

PYTHON_BIN_PATH="$(resolve_python_bin)"
log_info "worker python: ${PYTHON_BIN_PATH} ($("${PYTHON_BIN_PATH}" --version 2>&1))"

# 安全: 不使用 sudo -E，仅传递 ai_employee.py + SDK 所需的非敏感变量
# ZZD_MASTER_KEY 已 unset，不会泄露给 agent 进程
sudo -u agent -g "${WORKSPACE_EFFECTIVE_GROUP}" \
    env \
    WORKSTATION_ID="${WORKSTATION_ID}" \
    REDIS_URL="${REDIS_URL:-}" \
    CONFIG_FILE="${CONFIG_FILE}" \
    LOG_LEVEL="${LOG_LEVEL:-INFO}" \
    IDLE_TIMEOUT="${IDLE_TIMEOUT:-}" \
    WORKER_DESTROY_API_BASE="${WORKER_DESTROY_API_BASE:-}" \
    WORKER_DESTROY_API_PASSWORD="${WORKER_DESTROY_API_PASSWORD:-}" \
    ROBOT_LITELLM_API_KEY="${ROBOT_LITELLM_API_KEY:-}" \
    OPENAI_API_KEY="${OPENAI_API_KEY:-}" \
    LITELLM_API_KEY="${LITELLM_API_KEY:-}" \
    ANTHROPIC_AUTH_TOKEN="${ANTHROPIC_AUTH_TOKEN:-}" \
    ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-}" \
    POD_NAME="${POD_NAME:-}" \
    SERVICE_ID="${SERVICE_ID:-}" \
    PYTHON_BIN="${PYTHON_BIN_PATH}" \
    PYTHON="${PYTHON_BIN_PATH}" \
    UV_PYTHON="${PYTHON_BIN_PATH}" \
    PATH="${PATH}" \
    HOME="/home/agent" \
    bash -lc 'umask 0002; exec "${PYTHON_BIN}" /opt/agent/ai_employee.py' &
WORKER_PID=$!

# 终止 worker 进程树（WORKER_PID 指向 sudo 包装进程，不一定等于实际 Python 进程）
terminate_worker_tree() {
    local wait_sec=0
    local child_pids
    child_pids=$(ps -o pid= --ppid "$WORKER_PID" 2>/dev/null | tr '\n' ' ')

    # 优先终止 sudo 子进程（ai_employee.py），再终止 sudo 自身
    if [[ -n "${child_pids}" ]]; then
        kill -TERM ${child_pids} 2>/dev/null || true
    fi
    kill -TERM -- "-$WORKER_PID" 2>/dev/null || kill -TERM "$WORKER_PID" 2>/dev/null || true

    while [ $wait_sec -lt 5 ]; do
        local alive=0
        if kill -0 "$WORKER_PID" 2>/dev/null; then
            alive=1
        fi
        for pid in ${child_pids}; do
            if kill -0 "$pid" 2>/dev/null; then
                alive=1
                break
            fi
        done
        if [ $alive -eq 0 ]; then
            break
        fi
        sleep 1
        wait_sec=$((wait_sec + 1))
    done

    if kill -0 "$WORKER_PID" 2>/dev/null; then
        log_warn "Worker 未在 5s 内退出，发送 SIGKILL..."
        if [[ -n "${child_pids}" ]]; then
            kill -KILL ${child_pids} 2>/dev/null || true
        fi
        kill -KILL -- "-$WORKER_PID" 2>/dev/null || kill -KILL "$WORKER_PID" 2>/dev/null || true
        sleep 1
    fi

    wait "$WORKER_PID" 2>/dev/null || true
}

# =============================================================================
# 6. 信号处理 + 进程监控
# =============================================================================
cleanup() {
    log_info "收到停止信号，清理子进程..."
    terminate_worker_tree
    kill $ZZD_PID 2>/dev/null || true
    wait $ZZD_PID 2>/dev/null || true
    # 写 shutdown 标记，通知 Runner 容器退出（K8s 1.18 无原生 sidecar 支持）
    echo "agent_exit" > "${SHARED_KEY_DIR}/shutdown" 2>/dev/null || true
    log_info "清理完成，已写入 shutdown 标记"
}
trap cleanup EXIT SIGTERM SIGINT

# 同时监控 zzd 和 worker（wait -n 等待任一子进程退出，bash 4.3+）
# - zzd 先退出 → 错误，终止 worker，容器非零退出
# - worker 先退出 → 正常语义，返回 worker 退出码
# 注意: 整个监控块保持 set +e，避免对已回收 PID 再 wait 时触发 set -e
set +e
wait -n $WORKER_PID $ZZD_PID 2>/dev/null
FIRST_EXIT=$?

if ! kill -0 $ZZD_PID 2>/dev/null; then
    # zzd 先退出 — 异常（FIRST_EXIT 是 zzd 的退出码，或两者几乎同时退出）
    log_error "zzd 进程已退出 (code=$FIRST_EXIT)，终止 Worker..."
    terminate_worker_tree
    exit 1
fi

if ! kill -0 $WORKER_PID 2>/dev/null; then
    # worker 先退出 — 正常语义（FIRST_EXIT 是 worker 的退出码）
    log_info "Worker 退出 (code=$FIRST_EXIT)"
    # cleanup trap 会在 exit 时自动触发，写入 shutdown 标记通知 Runner
    exit $FIRST_EXIT
fi

# 兜底: wait -n 返回但两个进程都还活着（理论上不应发生）
log_error "进程监控状态异常 (FIRST_EXIT=$FIRST_EXIT, zzd_pid=$ZZD_PID, worker_pid=$WORKER_PID)"
exit 1
