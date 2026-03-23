#!/bin/bash
# =============================================================================
# Runner 容器入口脚本（基于 Rocky Linux 基础镜像）
#
# 职责: 准备 sshd 环境 → 等待 Agent 公钥 → 配置 SSH 认证 → 前台启动 sshd
#
# 适用场景:
#   - K8s Pod sidecar:  镜像应已预装 sshd（避免运行时安装）
#   - Docker Compose:   纯 Rocky 基础镜像，脚本自动兜底安装
#
# 运行时依赖:
#   - 共享卷 /shared-keys (Agent 写入 zzd_pubkey.pub)
#   - 共享卷 /workspace (工作目录)
#
# 重点:
#   - Runner 不负责 zzd/worker，只负责"被 SSH 执行"
#   - 这些步骤每次 Pod 重建都要重复（共享卷通常是 emptyDir，会清空）
#   - Agent 的 start-dual.sh 会等待 Runner SSH 就绪，然后再启动 zzd+worker
# =============================================================================
set -e

readonly SHARED_KEY_DIR="/shared-keys"
readonly PUBKEY_FILE="${SHARED_KEY_DIR}/zzd_pubkey.pub"
readonly PUBKEY_TIMEOUT="${PUBKEY_TIMEOUT:-120}"
readonly WORKSPACE_GROUP="${WORKSPACE_GROUP:-workspace}"
readonly WORKSPACE_GID="${WORKSPACE_GID:-2000}"
readonly RUNNER_USER="${RUNNER_USER:-runner}"
readonly RUNNER_UID="${RUNNER_UID:-1001}"
readonly RUNNER_HOME="${RUNNER_HOME:-/home/${RUNNER_USER}}"

# =============================================================================
# 日志
# =============================================================================
log_info()  { echo "[Runner][INFO]  $(date '+%H:%M:%S') $*"; }
log_error() { echo "[Runner][ERROR] $(date '+%H:%M:%S') $*" >&2; }
log_warn()  { echo "[Runner][WARN]  $(date '+%H:%M:%S') $*"; }

configure_runner_python_env() {
    local bashrc="${RUNNER_HOME}/.bashrc"
    local python_bin=""

    if [[ -x /usr/bin/python3.12 ]]; then
        python_bin="/usr/bin/python3.12"
        ln -sf /usr/bin/python3.12 /usr/local/bin/python3 2>/dev/null || true
        ln -sf /usr/bin/python3.12 /usr/local/bin/python 2>/dev/null || true
    elif command -v python3.12 >/dev/null 2>&1; then
        python_bin="$(command -v python3.12)"
    elif command -v python3 >/dev/null 2>&1; then
        python_bin="$(command -v python3)"
    fi

    if [[ -z "${python_bin}" ]]; then
        log_warn "未找到 python3.12/python3，跳过 ${RUNNER_USER} Python 环境变量注入"
        return 0
    fi

    sed -i '/# >>> workspace-python >>>/,/# <<< workspace-python <<</d' "${bashrc}" 2>/dev/null || true
    cat >> "${bashrc}" <<EOF
# >>> workspace-python >>>
export PYTHON_BIN="${python_bin}"
export PYTHON="${python_bin}"
export UV_PYTHON="${python_bin}"
export PATH="/usr/bin:/usr/local/bin:\$PATH"
# <<< workspace-python <<<
EOF
    chown "${RUNNER_USER}:${RUNNER_USER}" "${bashrc}"
    log_info "${RUNNER_USER} python 默认解释器: ${python_bin} ($("${python_bin}" --version 2>&1))"
}

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

    usermod -aG "${resolved_group}" "${RUNNER_USER}" || {
        log_error "将 ${RUNNER_USER} 加入 workspace 协作组失败 (${resolved_group})"
        return 1
    }

    for dir in /workspace /workspace/logs /workspace/user /workspace/workstation /workspace/task-logs /workspace/worker-logs; do
        mkdir -p "${dir}"
        chgrp -R "${resolved_group}" "${dir}"
        chmod -R g+rwX "${dir}"
        find "${dir}" -type d -exec chmod g+s {} +
        chmod 2770 "${dir}"
    done

    log_info "/workspace 权限已对齐 (group=${resolved_group}, dirs=workspace/logs/user/workstation/task-logs/worker-logs, umask=0002)"
}

# =============================================================================
# 1. 启动 sshd 所需环境（镜像内应已带好，兜底运行时安装）
# =============================================================================
log_info "================================================"
log_info " Runner 容器启动"
log_info " PUBKEY_TIMEOUT: ${PUBKEY_TIMEOUT}s"
log_info "================================================"

if [ ! -x /usr/sbin/sshd ]; then
    log_warn "sshd 未预装，运行时安装（生产镜像应预装以加速启动）..."
    dnf install -y openssh-server openssh-clients sudo && dnf clean all
    if [ ! -x /usr/sbin/sshd ]; then
        log_error "sshd 安装失败"
        exit 1
    fi
    log_info "sshd 运行时安装完成"
else
    log_info "sshd 已预装"
fi

# 生成 SSH host keys（如果不存在）
if [ ! -f /etc/ssh/ssh_host_rsa_key ] && [ ! -f /etc/ssh/ssh_host_ed25519_key ]; then
    log_info "生成 SSH host keys..."
    ssh-keygen -A
fi

# SSH 配置（幂等写入，多次执行不会重复追加）
log_info "配置 sshd_config..."
sed -i 's/^#*PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
sed -i 's/^#*PubkeyAuthentication.*/PubkeyAuthentication yes/' /etc/ssh/sshd_config
sed -i 's/^#*PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
grep -q '^AuthorizedKeysFile' /etc/ssh/sshd_config \
    || echo 'AuthorizedKeysFile .ssh/authorized_keys' >> /etc/ssh/sshd_config

# =============================================================================
# 2. 确保有执行用户（默认 runner，可通过 RUNNER_USER 覆盖，必要时 root 也可）
# =============================================================================
if id "${RUNNER_USER}" &>/dev/null; then
    CURRENT_UID="$(id -u "${RUNNER_USER}")"
    if [ "$CURRENT_UID" != "$RUNNER_UID" ]; then
        log_info "校准用户 ${RUNNER_USER} UID: ${CURRENT_UID} -> ${RUNNER_UID}"
        usermod -u "${RUNNER_UID}" "${RUNNER_USER}"
        chown -R "${RUNNER_USER}:${RUNNER_USER}" "${RUNNER_HOME}"
    else
        log_info "用户 ${RUNNER_USER} 已存在 (uid=${CURRENT_UID})"
    fi
else
    log_info "创建用户 ${RUNNER_USER} (uid=${RUNNER_UID})..."
    groupadd -g "${RUNNER_UID}" "${RUNNER_USER}" 2>/dev/null || true
    useradd -u "${RUNNER_UID}" -g "${RUNNER_USER}" -m -s /bin/bash "${RUNNER_USER}" 2>/dev/null || true
    echo "${RUNNER_USER} ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/${RUNNER_USER}"
    chmod 0440 "/etc/sudoers.d/${RUNNER_USER}"
    log_info "用户 ${RUNNER_USER} 已创建"
fi
log_info "${RUNNER_USER} 最终 uid=$(id -u "${RUNNER_USER}")"

if ! grep -q '^umask 0002$' "${RUNNER_HOME}/.bashrc" 2>/dev/null; then
    echo 'umask 0002' >> "${RUNNER_HOME}/.bashrc"
    chown "${RUNNER_USER}:${RUNNER_USER}" "${RUNNER_HOME}/.bashrc"
fi
configure_runner_python_env

# =============================================================================
# 3. 等待 Agent 写入共享卷公钥: /shared-keys/zzd_pubkey.pub
# =============================================================================
log_info "等待 Agent 公钥: ${PUBKEY_FILE} ..."
WAIT=0
while [ ! -f "$PUBKEY_FILE" ] && [ $WAIT -lt $PUBKEY_TIMEOUT ]; do
    sleep 1
    WAIT=$((WAIT + 1))
    if [ $((WAIT % 10)) -eq 0 ]; then
        log_info "  等待中... (${WAIT}/${PUBKEY_TIMEOUT}s)"
    fi
done

if [ ! -f "$PUBKEY_FILE" ]; then
    log_error "超时 (${PUBKEY_TIMEOUT}s): 未收到 Agent 公钥 ${PUBKEY_FILE}"
    log_error "请检查 Agent 容器是否正常启动并生成了密钥"
    exit 1
fi

log_info "检测到公钥: ${PUBKEY_FILE}"

# =============================================================================
# 4. 把公钥写到 authorized_keys（runner 用户 + root）
# =============================================================================
log_info "配置 SSH authorized_keys..."

# runner 用户
mkdir -p "${RUNNER_HOME}/.ssh"
cp "$PUBKEY_FILE" "${RUNNER_HOME}/.ssh/authorized_keys"
chown -R "${RUNNER_USER}:${RUNNER_USER}" "${RUNNER_HOME}/.ssh"
chmod 700 "${RUNNER_HOME}/.ssh"
chmod 600 "${RUNNER_HOME}/.ssh/authorized_keys"
log_info "  -> ${RUNNER_USER} authorized_keys 已配置"

# root 用户（可选，调试用）
mkdir -p /root/.ssh
cp "$PUBKEY_FILE" /root/.ssh/authorized_keys
chmod 700 /root/.ssh
chmod 600 /root/.ssh/authorized_keys
log_info "  -> root authorized_keys 已配置"

# =============================================================================
# 5. 准备 /workspace 权限
# =============================================================================
log_info "设置 /workspace 权限..."
mkdir -p /workspace
setup_workspace_group_permissions

# =============================================================================
# 6. 启动 SSH 服务并监控 Agent 退出信号
#
# K8s 1.18 无原生 sidecar 支持，Runner 需要主动感知 Agent 退出：
#   Agent 退出时写 /shared-keys/shutdown 标记
#   Runner 检测到标记后停止 sshd，容器退出，Pod 整体终止
# =============================================================================
readonly SHUTDOWN_MARKER="${SHARED_KEY_DIR}/shutdown"
readonly SHUTDOWN_CHECK_INTERVAL=5

log_info "================================================"
log_info " sshd 启动 (后台模式 + shutdown 监控)"
log_info "================================================"

/usr/sbin/sshd -D -e &
SSHD_PID=$!

shutdown_runner() {
    log_info "正在停止 sshd (pid=$SSHD_PID)..."
    kill -TERM "$SSHD_PID" 2>/dev/null || true
    wait "$SSHD_PID" 2>/dev/null || true
    log_info "Runner 已退出"
}
trap shutdown_runner EXIT SIGTERM SIGINT

while kill -0 "$SSHD_PID" 2>/dev/null; do
    if [ -f "$SHUTDOWN_MARKER" ]; then
        log_info "检测到 Agent shutdown 标记: ${SHUTDOWN_MARKER}"
        exit 0
    fi
    sleep "$SHUTDOWN_CHECK_INTERVAL"
done

SSHD_EXIT=$?
log_warn "sshd 意外退出 (code=$SSHD_EXIT)"
exit "$SSHD_EXIT"
