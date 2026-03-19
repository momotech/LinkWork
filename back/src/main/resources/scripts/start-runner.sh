#!/bin/bash
set -e

readonly SHARED_KEY_DIR="/shared-keys"
readonly PUBKEY_FILE="${SHARED_KEY_DIR}/zzd_pubkey.pub"
readonly PUBKEY_TIMEOUT="${PUBKEY_TIMEOUT:-120}"
readonly WORKSPACE_GROUP="${WORKSPACE_GROUP:-workspace}"
readonly WORKSPACE_GID="${WORKSPACE_GID:-2000}"
readonly MOMOBOT_UID="${MOMOBOT_UID:-1001}"
readonly SHUTDOWN_MARKER="${SHARED_KEY_DIR}/shutdown"
readonly SHUTDOWN_CHECK_INTERVAL=5

log_info()  { echo "[Runner][INFO]  $(date '+%H:%M:%S') $*"; }
log_error() { echo "[Runner][ERROR] $(date '+%H:%M:%S') $*" >&2; }
log_warn()  { echo "[Runner][WARN]  $(date '+%H:%M:%S') $*"; }

configure_momobot_python_env() {
    local bashrc="/home/momobot/.bashrc"
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
        log_warn "python3.12/python3 not found, skip momobot python env setup"
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
    chown momobot:momobot "${bashrc}"
    log_info "momobot python default interpreter: ${python_bin} ($("${python_bin}" --version 2>&1))"
}

setup_workspace_group_permissions() {
    local resolved_group="${WORKSPACE_GROUP}"

    if getent group "${WORKSPACE_GID}" >/dev/null 2>&1; then
        resolved_group=$(getent group "${WORKSPACE_GID}" | cut -d: -f1)
    elif ! getent group "${WORKSPACE_GROUP}" >/dev/null 2>&1; then
        groupadd -g "${WORKSPACE_GID}" "${WORKSPACE_GROUP}" || {
            log_error "failed to create workspace group (${WORKSPACE_GROUP}:${WORKSPACE_GID})"
            return 1
        }
    fi

    usermod -aG "${resolved_group}" momobot || {
        log_error "failed to add momobot into workspace group (${resolved_group})"
        return 1
    }

    for dir in /workspace /workspace/logs /workspace/user /workspace/workstation /workspace/task-logs /workspace/worker-logs; do
        mkdir -p "${dir}"
        chgrp -R "${resolved_group}" "${dir}"
        chmod -R g+rwX "${dir}"
        find "${dir}" -type d -exec chmod g+s {} +
        chmod 2770 "${dir}"
    done

    log_info "/workspace permissions prepared (group=${resolved_group})"
}

log_info "runner container booting..."

if [ ! -x /usr/sbin/sshd ]; then
    log_warn "sshd is missing, installing runtime packages..."
    dnf install -y openssh-server openssh-clients sudo && dnf clean all
    if [ ! -x /usr/sbin/sshd ]; then
        log_error "sshd install failed"
        exit 1
    fi
fi

if [ ! -f /etc/ssh/ssh_host_rsa_key ] && [ ! -f /etc/ssh/ssh_host_ed25519_key ]; then
    ssh-keygen -A
fi

sed -i 's/^#*PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
sed -i 's/^#*PubkeyAuthentication.*/PubkeyAuthentication yes/' /etc/ssh/sshd_config
sed -i 's/^#*PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
grep -q '^AuthorizedKeysFile' /etc/ssh/sshd_config \
    || echo 'AuthorizedKeysFile .ssh/authorized_keys' >> /etc/ssh/sshd_config

if id momobot &>/dev/null; then
    CURRENT_UID="$(id -u momobot)"
    if [ "$CURRENT_UID" != "$MOMOBOT_UID" ]; then
        usermod -u "${MOMOBOT_UID}" momobot
        chown -R momobot:momobot /home/momobot
    fi
else
    groupadd -g "${MOMOBOT_UID}" momobot 2>/dev/null || true
    useradd -u "${MOMOBOT_UID}" -g momobot -m -s /bin/bash momobot 2>/dev/null || true
    echo 'momobot ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/momobot
    chmod 0440 /etc/sudoers.d/momobot
fi

if ! grep -q '^umask 0002$' /home/momobot/.bashrc 2>/dev/null; then
    echo 'umask 0002' >> /home/momobot/.bashrc
    chown momobot:momobot /home/momobot/.bashrc
fi

configure_momobot_python_env

log_info "waiting for agent public key: ${PUBKEY_FILE}"
WAIT=0
while [ ! -f "$PUBKEY_FILE" ] && [ $WAIT -lt $PUBKEY_TIMEOUT ]; do
    sleep 1
    WAIT=$((WAIT + 1))
done

if [ ! -f "$PUBKEY_FILE" ]; then
    log_error "public key wait timeout (${PUBKEY_TIMEOUT}s): ${PUBKEY_FILE}"
    exit 1
fi

mkdir -p /home/momobot/.ssh /root/.ssh
cp "$PUBKEY_FILE" /home/momobot/.ssh/authorized_keys
chown -R momobot:momobot /home/momobot/.ssh
chmod 700 /home/momobot/.ssh
chmod 600 /home/momobot/.ssh/authorized_keys

cp "$PUBKEY_FILE" /root/.ssh/authorized_keys
chmod 700 /root/.ssh
chmod 600 /root/.ssh/authorized_keys

mkdir -p /workspace
setup_workspace_group_permissions

/usr/sbin/sshd -D -e &
SSHD_PID=$!

shutdown_runner() {
    kill -TERM "$SSHD_PID" 2>/dev/null || true
    wait "$SSHD_PID" 2>/dev/null || true
}
trap shutdown_runner EXIT SIGTERM SIGINT

while kill -0 "$SSHD_PID" 2>/dev/null; do
    if [ -f "$SHUTDOWN_MARKER" ]; then
        log_info "shutdown marker detected: ${SHUTDOWN_MARKER}"
        exit 0
    fi
    sleep "$SHUTDOWN_CHECK_INTERVAL"
done

exit $?
