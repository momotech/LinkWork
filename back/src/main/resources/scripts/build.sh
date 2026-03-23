#!/bin/bash
#
# K8s Agent 镜像构建脚本
#
# 功能：在镜像构建阶段执行，用于初始化工作环境
#   1. 克隆 Git 项目到 /workspace
#   2. 从 Git 仓库部署 Skills 到 /opt/agent/skills
#   3. 校验 Skills 依赖 (Python/Node.js/Go)
#   4. 部署 MCP 配置到 /opt/agent/mcp.json
#
#   构建时步骤:
#    1. 校验基础镜像内置依赖 (Python 3.12, Node.js, npm, git, Claude CLI, uv, uvx)
#    2. 创建基础目录
#    3. 安装 zzd 二进制 (zzd, zz, gen-key, encrypt-key)
#    4. 安装 linkwork-agent-sdk (源码)
#    5. 创建 agent 用户 + passwordless sudo
#    6. 创建 workspace 目录结构
#   6.1 部署 security.json/mcp.json/skills 到 /opt/agent/ (root:agent 只读)
#    7. 下载 Cedar 策略文件 (从 URL)
#    8. 创建 zzd 运行时目录
#    9. 校验 git 可用性
#   11. 克隆 Git 项目 (clone 后清除 token)
#   12. 下载 MCP 配置 (从 URL)
#   13. 下载 Skills (从 URL)
#   14. 校验 Skills 依赖
#   15. 部署 start.sh 和 ai_employee.py 到 /opt/agent/ (root:root)
#   16. 最终权限设置 (/opt/agent/ config 0440 root:agent, /etc/zzd 0700 root:root)
#   17. 清理构建临时文件
#
# 构建时环境变量（由构建系统通过 Dockerfile ARG 传入）：
#   GIT_TOKEN            - Git 认证 Token
#   GIT_REPOS            - 待克隆的 Git 仓库列表 (JSON 数组格式)
#   CEDAR_POLICIES_URL   - Cedar 策略文件压缩包 URL
#   MCP_CONFIG_URL       - MCP 配置文件 URL (JSON)
#   CONFIG_URL           - [预留，未启用] Agent config.json URL（后续可支持构建期远端下载）
#   SKILLS_URL           - Skills 压缩包的 OSS 链接
# 环境变量（由构建系统导出）：
#   GIT_TOKEN       - Git 认证 Token
#   GIT_REPOS       - 待克隆的 Git 仓库列表 (JSON 数组格式)
#                     示例: '[{"url":"https://git.example.com/repo1.git","branch":"main"}]'
#   SKILLS_CONFIG   - Skills Git 配置 JSON（优先级最高）
#                     格式: {"repoUrl":"...","token":"...","skills":[{"name":"...","branch":"...","commit":"..."}]}
#   SKILLS_URL      - Skills 压缩包的 OSS 链接（SKILLS_CONFIG 为空时回退使用）
#   MCP_CONFIG      - MCP 配置 JSON 字符串（由后端从岗位 configJson.mcp 生成，优先级最高）
#   MCP_CONFIG_URL  - MCP 配置文件 URL（MCP_CONFIG 为空时回退使用）
#
# 构建输入目录（支持两种来源）：
#   1) 固定目录（推荐）：$BUILD_ASSETS_ROOT
#      - zzd-binaries/    - zzd, zz, gen-key, encrypt-key
#      - sdk-source/      - linkwork-agent-sdk 源码 (含 pyproject.toml + src/)
#      - start-scripts/   - start-single.sh, start-dual.sh, ai_employee.py
#   2) 兼容旧路径（若固定目录不存在则回退）：/tmp/*
#

set -o pipefail

# =============================================================================
# 常量定义
# =============================================================================
readonly WORKSPACE_DIR="/workspace"
WORKSPACE_GROUP="${WORKSPACE_GROUP:-workspace}"
WORKSPACE_GID="${WORKSPACE_GID:-2000}"
readonly AGENT_CONFIG_DIR="/opt/agent"
readonly SKILLS_DIR="${AGENT_CONFIG_DIR}/skills"
readonly MCP_CONFIG_FILE="${AGENT_CONFIG_DIR}/mcp.json"
readonly SECURITY_FILE="${AGENT_CONFIG_DIR}/security.json"
readonly SKILLS_ARCHIVE="/tmp/skills.tar.gz"
readonly CEDAR_ARCHIVE="/tmp/cedar-policies.tar.gz"

# zzd 相关路径
readonly ZZD_CONFIG_DIR="/etc/zzd"
readonly ZZD_POLICY_DIR="${ZZD_CONFIG_DIR}/policies"
readonly ZZD_SOCKET_DIR="/var/run/zzd"
readonly ZZD_AUDIT_DIR="/var/log/zzd/audit"

# 构建输入根目录（固定目录，可通过环境变量覆盖）
readonly BUILD_ASSETS_ROOT="${BUILD_ASSETS_ROOT:-/opt/linkwork-agent-build}"
readonly LEGACY_TMP_ROOT="/tmp"

# 输入路径（优先固定目录，不存在时回退旧 /tmp 路径）
ZZD_BIN_SRC="${ZZD_BIN_SRC:-${BUILD_ASSETS_ROOT}/zzd-binaries}"
SDK_SRC="${SDK_SRC:-${BUILD_ASSETS_ROOT}/sdk-source}"
START_SCRIPTS_SRC="${START_SCRIPTS_SRC:-${BUILD_ASSETS_ROOT}/start-scripts}"

if [[ ! -d "${ZZD_BIN_SRC}" && -d "${LEGACY_TMP_ROOT}/zzd-binaries" ]]; then
    ZZD_BIN_SRC="${LEGACY_TMP_ROOT}/zzd-binaries"
fi
if [[ ! -d "${SDK_SRC}" && -d "${LEGACY_TMP_ROOT}/sdk-build" ]]; then
    SDK_SRC="${LEGACY_TMP_ROOT}/sdk-build"
fi
if [[ ! -d "${SDK_SRC}" && -d "${LEGACY_TMP_ROOT}/linkwork-agent-sdk" ]]; then
    SDK_SRC="${LEGACY_TMP_ROOT}/linkwork-agent-sdk"
fi
if [[ ! -d "${START_SCRIPTS_SRC}" && -d "${LEGACY_TMP_ROOT}/start-scripts" ]]; then
    START_SCRIPTS_SRC="${LEGACY_TMP_ROOT}/start-scripts"
fi

readonly ZZD_BIN_SRC SDK_SRC START_SCRIPTS_SRC

# 颜色输出
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly NC='\033[0m' # No Color

# =============================================================================
# 日志函数
# =============================================================================
log_info() {
    echo -e "${BLUE}[INFO]${NC} $(date '+%Y-%m-%d %H:%M:%S') $*"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $(date '+%Y-%m-%d %H:%M:%S') $*"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $(date '+%Y-%m-%d %H:%M:%S') $*"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $(date '+%Y-%m-%d %H:%M:%S') $*" >&2
}

# =============================================================================
# 工具函数
# =============================================================================

# URL 脱敏 — 剥离 query string、fragment 和 userinfo，仅保留 scheme://host/path
# 用途: 日志输出 URL 时防止泄露签名、token 等敏感参数
redact_url() {
    local url="$1"
    # 去掉 query string (?...) 和 fragment (#...)
    url="${url%%\?*}"
    url="${url%%#*}"
    # 去掉 userinfo (oauth2:TOKEN@ 等)
    url=$(echo "${url}" | sed -E 's|(https?://)([^@]+@)|\1|')
    echo "${url}"
}

# 检查命令是否存在（静默模式）
command_exists() {
    command -v "$1" &> /dev/null
}

# 安装系统依赖（禁用：基础镜像已预装）
install_system_dependencies() {
    log_error "检测到缺失依赖，但当前模式禁用在线安装。请在基础镜像中预装依赖后重试。"
    return 1
}

# 检查 Claude Code CLI（禁用在线安装）
install_claude_cli() {
    if command_exists claude; then
        log_success "Claude Code CLI 已安装"
        return 0
    fi
    log_error "claude 命令缺失。当前模式禁用在线安装，请在基础镜像中预装 Claude CLI。"
    return 1
}

# 检查基础镜像内置依赖（不安装）
check_prerequisites() {
    log_info "检查基础镜像内置依赖（不执行在线安装）..."

    # pi CLI 在部分基础镜像中未内置，当前构建流程不依赖其作为硬门槛。
    local required_cmds=("curl" "jq" "python3.12" "node" "npm" "git" "claude" "uv" "uvx")
    local missing=()

    for cmd in "${required_cmds[@]}"; do
        if ! command_exists "$cmd"; then
            missing+=("$cmd")
        fi
    done

    if [[ ${#missing[@]} -gt 0 ]]; then
        log_error "缺少必需命令: ${missing[*]}"
        log_error "请确保基础镜像已预装: Python 3.12 / Node.js v24.13.0 / npm 11.6.2 / git 2.43.5 / Claude CLI / uv / uvx"
        return 1
    fi

    if ! python3.12 -m pip --version >/dev/null 2>&1; then
        log_error "python3.12 -m pip 不可用，请确保基础镜像内置 pip"
        return 1
    fi

    log_info "依赖版本信息:"
    log_info "  $(python3.12 --version 2>&1)"
    log_info "  $(node --version 2>&1)"
    log_info "  npm $(npm --version 2>&1)"
    log_info "  $(git --version 2>&1)"
    log_info "  $(claude --version 2>&1 | head -1)"
    if command_exists pi; then
        log_info "  $(pi --version 2>&1 | head -1)"
    else
        log_warn "pi 命令未安装，按可选依赖处理"
    fi
    log_info "  $(uv --version 2>&1)"
    log_info "  $(uvx --version 2>&1 | head -1)"

    # 版本提示（不强制中断）
    local py_ver node_ver npm_ver git_ver
    py_ver=$(python3.12 --version 2>&1 | awk '{print $2}')
    node_ver=$(node --version 2>&1)
    npm_ver=$(npm --version 2>&1)
    git_ver=$(git --version 2>&1 | awk '{print $3}')

    [[ "${py_ver}" == 3.12* ]] || log_warn "Python 版本不是 3.12.x: ${py_ver}"
    [[ "${node_ver}" == "v24.13.0" ]] || log_warn "Node.js 版本不是 v24.13.0: ${node_ver}"
    [[ "${npm_ver}" == "11.6.2" ]] || log_warn "npm 版本不是 11.6.2: ${npm_ver}"
    [[ "${git_ver}" == "2.43.5" ]] || log_warn "git 版本不是 2.43.5: ${git_ver}"

    # ★ 修复 python3 符号链接：确保 /usr/bin/python3 → python3.12
    # Rocky Linux alternatives 可能残留 python3 → python3.11（不存在），
    # start-dual.sh 用 "sudo -u agent python3 ..." 需要 python3 可用
    if ! python3 --version &>/dev/null || ! python3 -c "import sys" &>/dev/null; then
        log_warn "python3 不可用或指向错误目标，修复符号链接..."
        alternatives --remove-all python3 2>/dev/null || true
        rm -f /etc/alternatives/python3 /usr/bin/python3 /usr/bin/python
        ln -s /usr/bin/python3.12 /usr/bin/python3
        ln -s /usr/bin/python3.12 /usr/bin/python
        log_info "python3 -> python3.12 ($(python3 --version 2>&1))"
    elif [[ "$(readlink -f /usr/bin/python3 2>/dev/null)" != "/usr/bin/python3.12" ]]; then
        log_warn "python3 未指向 python3.12，修复..."
        alternatives --remove-all python3 2>/dev/null || true
        rm -f /etc/alternatives/python3 /usr/bin/python3 /usr/bin/python
        ln -s /usr/bin/python3.12 /usr/bin/python3
        ln -s /usr/bin/python3.12 /usr/bin/python
        log_info "python3 -> python3.12 ($(python3 --version 2>&1))"
    else
        log_success "python3 -> $(readlink -f /usr/bin/python3) (OK)"
    fi

    log_success "依赖检查完成"
    return 0
}

# 创建必要目录
create_directories() {
    log_info "创建工作目录..."

    mkdir -p "${WORKSPACE_DIR}" || {
        log_error "创建 ${WORKSPACE_DIR} 失败"
        return 1
    }

    mkdir -p "${AGENT_CONFIG_DIR}" || {
        log_error "创建 ${AGENT_CONFIG_DIR} 失败"
        return 1
    }

    mkdir -p "${SKILLS_DIR}" || {
        log_error "创建 ${SKILLS_DIR} 失败"
        return 1
    }

    # 确保 MCP 配置文件父目录存在
    mkdir -p "$(dirname "${MCP_CONFIG_FILE}")" || {
        log_error "创建 $(dirname "${MCP_CONFIG_FILE}") 失败"
        return 1
    }

    log_success "目录结构已创建"
    return 0
}

# =============================================================================
# Phase 2: zzd / SDK / 用户
# =============================================================================

# 安装 zzd 二进制文件
install_zzd_binaries() {
    log_info "安装 zzd 二进制文件..."

    local binaries=("zzd" "zz" "gen-key" "encrypt-key")

    if [[ ! -d "${ZZD_BIN_SRC}" ]]; then
        local missing=()
        for bin in "${binaries[@]}"; do
            if ! command_exists "${bin}"; then
                missing+=("${bin}")
            fi
        done
        if [[ ${#missing[@]} -eq 0 ]]; then
            log_warn "未找到 ${ZZD_BIN_SRC}，但基础镜像已内置 zzd 二进制，跳过复制"
            return 0
        fi

        log_error "zzd 二进制目录不存在: ${ZZD_BIN_SRC}"
        log_error "请将 zzd/zz/gen-key/encrypt-key 放置到固定目录，或通过 ZZD_BIN_SRC 指定路径"
        return 1
    fi

    for bin in "${binaries[@]}"; do
        if [[ ! -f "${ZZD_BIN_SRC}/${bin}" ]]; then
            log_error "二进制文件不存在: ${ZZD_BIN_SRC}/${bin}"
            return 1
        fi
    done

    for bin in "${binaries[@]}"; do
        cp "${ZZD_BIN_SRC}/${bin}" "/usr/local/bin/${bin}"
        chmod +x "/usr/local/bin/${bin}"
        # 创建 /usr/bin/ 符号链接，确保 agent 用户 PATH 可达
        ln -sf "/usr/local/bin/${bin}" "/usr/bin/${bin}"
    done

    log_success "zzd 二进制安装完成 (${#binaries[@]}/${#binaries[@]})"
    return 0
}

# 安装 linkwork-agent-sdk
install_sdk() {
    log_info "安装 linkwork-agent-sdk..."

    # 固定使用 Python 3.12（基础镜像内置）
    local python_cmd="python3.12"
    if ! command_exists "${python_cmd}"; then
        log_error "未找到 ${python_cmd}"
        return 1
    fi

    if [[ ! -d "${SDK_SRC}" ]]; then
        if ${python_cmd} -c "import linkwork_agent_sdk" >/dev/null 2>&1; then
            log_warn "未找到 SDK 源码目录，检测到基础镜像已安装 linkwork_agent_sdk，跳过安装"
            return 0
        fi

        log_error "SDK 源码目录不存在: ${SDK_SRC}"
        log_error "请将 linkwork-agent-sdk 源码放置到固定目录，或通过 SDK_SRC 指定路径"
        return 1
    fi

    if [[ ! -f "${SDK_SRC}/pyproject.toml" ]]; then
        if ${python_cmd} -c "import linkwork_agent_sdk" >/dev/null 2>&1; then
            log_warn "${SDK_SRC}/pyproject.toml 不存在，但基础镜像已安装 linkwork_agent_sdk，跳过安装"
            return 0
        fi

        log_error "${SDK_SRC}/pyproject.toml 不存在"
        return 1
    fi

    log_info "使用 ${python_cmd} 安装 SDK..."
    # --no-deps: 运行时依赖已在基础镜像中预装，不再下载
    # --no-build-isolation: 使用系统已装的 setuptools/wheel 构建，不联网
    ${python_cmd} -m pip install --no-cache-dir --no-deps --no-build-isolation "${SDK_SRC}" || {
        log_error "SDK 安装失败"
        return 1
    }

    log_success "linkwork-agent-sdk 安装完成"
    return 0
}

# 修补 linkwork_agent_sdk 运行时模型选择：
# 当 config.json/env 提供 ANTHROPIC_MODEL 时，优先使用该完整模型名，
# 避免 claude-sdk 将 "sonnet" 展开为不被 LiteLLM 识别的别名。
patch_sdk_runtime_model_override() {
    local python_cmd="python3.12"

    if ! command_exists "${python_cmd}"; then
        log_warn "未找到 ${python_cmd}，跳过 SDK runtime model 补丁"
        return 0
    fi

    local engine_file
    engine_file="$(${python_cmd} - <<'PY'
import importlib
try:
    m = importlib.import_module("linkwork_agent_sdk.engine.agent_engine")
    print(getattr(m, "__file__", ""))
except Exception:
    print("")
PY
)"

    if [[ -z "${engine_file}" || ! -f "${engine_file}" ]]; then
        log_warn "未定位到 linkwork_agent_sdk.engine.agent_engine，跳过补丁"
        return 0
    fi

    if grep -q 'resolved_env.get("ANTHROPIC_MODEL")' "${engine_file}"; then
        log_info "SDK runtime model 补丁已存在，跳过"
        return 0
    fi

    if ! grep -q '"model": self._config.claude_settings.model,' "${engine_file}"; then
        log_warn "未匹配到预期模型配置行，跳过 SDK runtime model 补丁: ${engine_file}"
        return 0
    fi

    sed -i \
        's/"model": self._config.claude_settings.model,/"model": (resolved_env.get("ANTHROPIC_MODEL") if resolved_env and resolved_env.get("ANTHROPIC_MODEL") else self._config.claude_settings.model),/' \
        "${engine_file}" || {
        log_error "应用 SDK runtime model 补丁失败: ${engine_file}"
        return 1
    }

    log_success "SDK runtime model 补丁已应用: ${engine_file}"
    return 0
}

# 创建 agent 用户 + 配置 sudoers
setup_agent_user() {
    log_info "创建 agent 用户..."

    # 创建用户（如果不存在）
    if id agent &>/dev/null; then
        log_info "agent 用户已存在，跳过创建"
    else
        groupadd -g 1001 agent || {
            log_error "创建 agent 用户组失败"
            return 1
        }

        useradd -u 1001 -g agent -m -s /bin/bash agent || {
            log_error "创建 agent 用户失败"
            return 1
        }

        log_success "agent 用户创建完成 (uid=1001)"
    fi

    # 创建 monitor 用户（专用于运行 zzd 守护进程，降低 zzd 运行权限）
    if id monitor &>/dev/null; then
        log_info "monitor 用户已存在，跳过创建"
    else
        groupadd -g 1002 monitor || {
            log_error "创建 monitor 用户组失败"
            return 1
        }

        useradd -u 1002 -g monitor -M -r -s /sbin/nologin monitor || {
            log_error "创建 monitor 用户失败"
            return 1
        }

        log_success "monitor 用户创建完成 (uid=1002, nologin)"
    fi

    # 创建/复用 workspace 协作组（用于 agent/runner 跨容器共享写入）
    if getent group "${WORKSPACE_GID}" >/dev/null 2>&1; then
        local gid_group
        gid_group=$(getent group "${WORKSPACE_GID}" | cut -d: -f1)
        if [[ -n "${gid_group}" && "${gid_group}" != "${WORKSPACE_GROUP}" ]]; then
            log_warn "WORKSPACE_GID=${WORKSPACE_GID} 已被组 ${gid_group} 占用，复用该组"
            WORKSPACE_GROUP="${gid_group}"
        fi
    elif getent group "${WORKSPACE_GROUP}" >/dev/null 2>&1; then
        local group_gid
        group_gid=$(getent group "${WORKSPACE_GROUP}" | cut -d: -f3)
        if [[ -n "${group_gid}" && "${group_gid}" != "${WORKSPACE_GID}" ]]; then
            log_warn "组 ${WORKSPACE_GROUP} 已存在且 gid=${group_gid}，将使用该 gid"
            WORKSPACE_GID="${group_gid}"
        fi
    else
        groupadd -g "${WORKSPACE_GID}" "${WORKSPACE_GROUP}" || {
            log_error "创建 workspace 协作组失败 (${WORKSPACE_GROUP}:${WORKSPACE_GID})"
            return 1
        }
    fi
    usermod -aG "${WORKSPACE_GROUP}" agent || {
        log_error "将 agent 加入 workspace 协作组失败 (${WORKSPACE_GROUP})"
        return 1
    }
    log_info "  -> workspace 协作组: ${WORKSPACE_GROUP}(gid=${WORKSPACE_GID}), agent 已加入"

    # zzd sudoers: 只允许 monitor(zzd) 以 agent 身份执行命令
    # 参考: docs/zzd/zzd.md §安全机制 — agent 用户无 sudo 权限, zzd 以 monitor 身份运行
    rm -f /etc/sudoers.d/agent 2>/dev/null || true  # 清除可能存在的错误配置
    echo 'monitor ALL=(agent) NOPASSWD: ALL' > /etc/sudoers.d/zzd
    chmod 0440 /etc/sudoers.d/zzd
    chown root:root /etc/sudoers.d/zzd
    log_info "  -> sudoers 配置已更新 (/etc/sudoers.d/zzd: root -> agent)"

    # 写入 Claude CLI 配置，跳过 onboarding 认证流程
    local agent_home="/home/agent"
    mkdir -p "${agent_home}/.claude"
    echo '{"hasCompletedOnboarding": true}' > "${agent_home}/.claude.json"
    chown agent:agent "${agent_home}/.claude.json"
    chown -R agent:agent "${agent_home}/.claude"
    log_info "  -> 写入 .claude.json (hasCompletedOnboarding: true)"

    return 0
}

# =============================================================================
# Phase 3: workspace + zzd 配置
# =============================================================================

# 创建 workspace 目录结构
setup_workspace() {
    log_info "创建 workspace 目录结构..."

    mkdir -p "${WORKSPACE_DIR}/logs" \
             "${WORKSPACE_DIR}/user" \
             "${WORKSPACE_DIR}/workstation" \
             "${WORKSPACE_DIR}/task-logs" \
             "${WORKSPACE_DIR}/worker-logs" || {
        log_error "workspace 目录创建失败"
        return 1
    }

    log_success "workspace 目录结构创建完成"
    return 0
}

# 部署 agent 配置文件到 /opt/agent/ (root:agent 只读)
setup_agent_config() {
    log_info "部署 agent 配置到 ${AGENT_CONFIG_DIR}..."

    mkdir -p "${SKILLS_DIR}/default" || {
        log_error "${SKILLS_DIR}/default 目录创建失败"
        return 1
    }

    # 默认 security.json (SDK 需要此文件存在)
    if [[ ! -f "${SECURITY_FILE}" ]]; then
        echo '{"rules": []}' > "${SECURITY_FILE}"
        log_info "  -> 创建默认 security.json"
    fi

    # 默认 mcp.json (SDK 需要 mcpServers 字段)
    if [[ ! -f "${MCP_CONFIG_FILE}" ]]; then
        echo '{"mcpServers": {}}' > "${MCP_CONFIG_FILE}"
        log_info "  -> 创建默认 mcp.json"
    fi

    # 占位 SKILL.md (SDK 需要至少一个有效 skill，含 YAML frontmatter)
    local default_skill="${SKILLS_DIR}/default/SKILL.md"
    if [[ ! -f "${default_skill}" ]]; then
        printf '%s\n' '---' 'name: default' 'description: Default placeholder skill' '---' '' '# Default Skill' '' 'Placeholder skill.' > "${default_skill}"
        log_info "  -> 创建占位 SKILL.md"
    fi

    log_success "agent 配置部署完成"
    return 0
}

# 下载 Cedar 策略文件（从 URL 下载，回退到 /tmp/cedar-policies/ COPY 方式）
download_cedar_policies() {
    log_info "下载/部署 Cedar 策略文件..."

    mkdir -p "${ZZD_POLICY_DIR}"

    local policy_tmp="/tmp/cedar-policies-download"

    if [[ -n "${CEDAR_POLICIES_URL}" ]]; then
        # 方式 1: 从 URL 下载
        log_info "从 $(redact_url "${CEDAR_POLICIES_URL}") 下载 Cedar 策略..."

        if ! curl -fsSL -o "${CEDAR_ARCHIVE}" "${CEDAR_POLICIES_URL}"; then
            log_error "Cedar 策略下载失败"
            return 1
        fi

        mkdir -p "${policy_tmp}"

        # 解压（支持 tar.gz 和 zip）
        if file "${CEDAR_ARCHIVE}" | grep -q "gzip"; then
            tar -xzf "${CEDAR_ARCHIVE}" -C "${policy_tmp}" --strip-components=0 || {
                log_error "Cedar 策略解压失败 (tar.gz)"
                return 1
            }
        elif file "${CEDAR_ARCHIVE}" | grep -q "Zip"; then
            unzip -o "${CEDAR_ARCHIVE}" -d "${policy_tmp}" || {
                log_error "Cedar 策略解压失败 (zip)"
                return 1
            }
        else
            tar -xzf "${CEDAR_ARCHIVE}" -C "${policy_tmp}" --strip-components=0 || {
                log_error "Cedar 策略解压失败 (未知格式)"
                return 1
            }
        fi

        rm -f "${CEDAR_ARCHIVE}"

        # 部署 .cedar 文件
        local count=0
        for f in "${policy_tmp}"/*.cedar; do
            [[ -f "$f" ]] || continue
            cp "$f" "${ZZD_POLICY_DIR}/"
            ((count++))
        done

        rm -rf "${policy_tmp}"

        if [[ ${count} -eq 0 ]]; then
            log_warn "下载的压缩包中无 .cedar 文件"
            return 0
        fi

    elif [[ -d "/tmp/cedar-policies" ]]; then
        # 方式 2: 回退到 Dockerfile COPY 的文件
        log_info "使用 /tmp/cedar-policies/ 中的策略文件 (COPY 方式)..."

        local count=0
        for f in /tmp/cedar-policies/*.cedar; do
            [[ -f "$f" ]] || continue
            cp "$f" "${ZZD_POLICY_DIR}/"
            ((count++))
        done

        if [[ ${count} -eq 0 ]]; then
            log_warn "/tmp/cedar-policies/ 中无 .cedar 文件"
            return 0
        fi
    else
        log_warn "CEDAR_POLICIES_URL 为空且 /tmp/cedar-policies/ 不存在，跳过 Cedar 策略部署"
        return 0
    fi

    # 清理 macOS 资源 fork 文件
    find "${ZZD_POLICY_DIR}" -name '._*' -delete 2>/dev/null || true

    # 策略文件 root only, agent 不可读
    if ls "${ZZD_POLICY_DIR}"/*.cedar &>/dev/null; then
        chmod 0400 "${ZZD_POLICY_DIR}"/*.cedar
        chown root:root "${ZZD_POLICY_DIR}"/*.cedar
        local final_count
        final_count=$(ls -1 "${ZZD_POLICY_DIR}"/*.cedar 2>/dev/null | wc -l)
        log_success "Cedar 策略部署完成 (${final_count} 个文件, 0400 root:root)"
    fi

    return 0
}

# 创建 zzd 运行时目录
setup_zzd_directories() {
    log_info "创建 zzd 运行时目录..."

    mkdir -p "${ZZD_CONFIG_DIR}" \
             "${ZZD_POLICY_DIR}" \
             "${ZZD_SOCKET_DIR}" \
             "${ZZD_AUDIT_DIR}" || {
        log_error "zzd 目录创建失败"
        return 1
    }

    # /etc/zzd 及子目录 — root:monitor, monitor(zzd) 可读, agent 不可读不可改
    chmod 0750 "${ZZD_CONFIG_DIR}"
    chmod 0750 "${ZZD_POLICY_DIR}"
    chown -R root:monitor "${ZZD_CONFIG_DIR}"

    # socket 和 audit 目录归 monitor 用户（zzd 进程以 monitor 身份写入）
    chown monitor:monitor "${ZZD_SOCKET_DIR}"
    chmod 0755 "${ZZD_SOCKET_DIR}"
    chown monitor:monitor "${ZZD_AUDIT_DIR}"
    chmod 0700 "${ZZD_AUDIT_DIR}"

    log_success "zzd 运行时目录创建完成 (config: 0750 root:monitor, socket/audit: monitor:monitor)"
    return 0
}

# =============================================================================
# Phase 4: Git
# =============================================================================

# 校验 git（基础镜像应已预装）
install_git() {
    log_info "校验 git..."

    if ! command_exists git; then
        log_error "git 未安装。当前模式禁用在线安装，请在基础镜像中预装 git 2.43.5"
        return 1
    fi

    log_success "git 已安装: $(git --version)"
    return 0
}

# 克隆 Git 项目
clone_git_repos() {
    log_info "开始克隆 Git 仓库..."

    if [[ -z "${GIT_REPOS}" ]]; then
        log_warn "GIT_REPOS 环境变量为空，跳过 Git 克隆"
        return 0
    fi

    if [[ -z "${GIT_TOKEN}" ]]; then
        log_warn "GIT_TOKEN 环境变量为空，将使用无认证方式克隆"
    fi

    # 解析 JSON 数组
    local repo_count
    repo_count=$(echo "${GIT_REPOS}" | jq -r 'length')

    if [[ "${repo_count}" == "null" ]] || [[ "${repo_count}" -eq 0 ]]; then
        log_warn "GIT_REPOS 为空或格式错误，跳过 Git 克隆"
        return 0
    fi

    log_info "共有 ${repo_count} 个仓库需要克隆"

    local success_count=0
    local fail_count=0

    for ((i=0; i<repo_count; i++)); do
        local repo_url repo_branch repo_name clone_url

        repo_url=$(echo "${GIT_REPOS}" | jq -r ".[$i].url")
        repo_branch=$(echo "${GIT_REPOS}" | jq -r ".[$i].branch // \"main\"")

        # 从 URL 提取仓库名
        repo_name=$(basename "${repo_url}" .git)

        # 安全: 校验目录名，防止恶意 URL 覆盖关键目录
        if [[ ! "${repo_name}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
            log_error "仓库目录名不合法: '${repo_name}' (来源: $(redact_url "${repo_url}"))"
            ((fail_count++))
            continue
        fi
        # 保留目录黑名单 — 不允许覆盖 workspace 下的关键目录
        case "${repo_name}" in
            logs|user|workstation|task-logs|worker-logs)
                log_error "仓库目录名与保留目录冲突: '${repo_name}'"
                ((fail_count++))
                continue
                ;;
        esac

        if [[ -n "${GIT_TOKEN}" ]] && [[ "${repo_url}" == https://* ]]; then
            # 格式: https://oauth2:TOKEN@git.example.com/repo.git
            clone_url=$(echo "${repo_url}" | sed "s|https://|https://oauth2:${GIT_TOKEN}@|")
        else
            clone_url="${repo_url}"
        fi

        log_info "[$((i+1))/${repo_count}] 克隆仓库: ${repo_name} (分支: ${repo_branch})"

        local target_dir="${WORKSPACE_DIR}/${repo_name}"

        # 如果目录已存在，先删除
        if [[ -d "${target_dir}" ]]; then
            log_warn "目录 ${target_dir} 已存在，将删除后重新克隆"
            rm -rf "${target_dir}"
        fi

        # 克隆（重定向输出避免 token URL 泄露到构建日志）
        if git clone --branch "${repo_branch}" --single-branch --depth 1 "${clone_url}" "${target_dir}" >/dev/null 2>&1; then
            # 安全: 清除 .git/config 中的 token URL，防止 agent 读取
            if [[ -n "${GIT_TOKEN}" ]]; then
                git -C "${target_dir}" remote set-url origin "${repo_url}" 2>/dev/null || true
            fi
            log_success "仓库 ${repo_name} 克隆成功"
            ((success_count++))
        else
            log_error "仓库 ${repo_name} 克隆失败 (url=$(redact_url "${repo_url}"))"
            ((fail_count++))
        fi
    done

    log_info "Git 克隆完成: 成功 ${success_count}，失败 ${fail_count}"

    if [[ ${fail_count} -gt 0 ]]; then
        return 1
    fi

    return 0
}

# =============================================================================
# Phase 5: MCP + Skills
# =============================================================================
# wangfenghe 20260226 该代码未知定义 暂时注释
# 下载 MCP 配置（从 URL 下载）
#download_mcp_config() {
#    log_info "下载 MCP 配置..."
#
#    if [[ -z "${MCP_CONFIG_URL}" ]]; then
#        log_warn "MCP_CONFIG_URL 为空，使用默认 MCP 配置"
#        return 0
#    fi
#
#    log_info "从 $(redact_url "${MCP_CONFIG_URL}") 下载 MCP 配置..."
#
#    local tmp_mcp="/tmp/mcp_download.json"
#
#    if ! curl -fsSL -o "${tmp_mcp}" "${MCP_CONFIG_URL}"; then
#        log_error "MCP 配置下载失败"
#        return 1
#    fi
#
#    # 验证 JSON 格式
#    if ! jq empty "${tmp_mcp}" 2>/dev/null; then
#        log_error "下载的 MCP 配置不是有效的 JSON 格式"
#        rm -f "${tmp_mcp}"
#        return 1
#    fi
#
#    # 写入配置文件（格式化）
#    jq '.' "${tmp_mcp}" > "${MCP_CONFIG_FILE}" || {
#        log_error "写入 MCP 配置文件失败"
#        rm -f "${tmp_mcp}"
#        return 1
#    }
#
#    rm -f "${tmp_mcp}"
#
#    # 设置权限
#    chmod 600 "${MCP_CONFIG_FILE}"
#
#    log_success "MCP 配置已部署到 ${MCP_CONFIG_FILE}"
#    return 0
#}

# 下载并部署 Skills
download_skills() {
    log_info "开始下载 Skills..."

    if [[ -z "${SKILLS_URL}" ]]; then
        log_warn "SKILLS_URL 环境变量为空，跳过 Skills 下载"
        return 0
    fi

    log_info "从 $(redact_url "${SKILLS_URL}") 下载 Skills..."

    if ! curl -fsSL -o "${SKILLS_ARCHIVE}" "${SKILLS_URL}"; then
        log_error "Skills 下载失败"
        return 1
    fi

    log_success "Skills 下载完成"

    # 解压到 skills 目录
    log_info "解压 Skills 到 ${SKILLS_DIR}..."

    # 清空目标目录（保留 default skill）
    find "${SKILLS_DIR}" -mindepth 1 -maxdepth 1 -type d ! -name default -exec rm -rf {} \; 2>/dev/null || true

    # 解压（支持 tar.gz 和 zip 格式）
    if file "${SKILLS_ARCHIVE}" | grep -q "gzip"; then
        tar -xzf "${SKILLS_ARCHIVE}" -C "${SKILLS_DIR}" --strip-components=0 || {
            log_error "Skills 解压失败 (tar.gz)"
            return 1
        }
    elif file "${SKILLS_ARCHIVE}" | grep -q "Zip"; then
        unzip -o "${SKILLS_ARCHIVE}" -d "${SKILLS_DIR}" || {
            log_error "Skills 解压失败 (zip)"
            return 1
        }
    else
        # 尝试 tar.gz 解压
        tar -xzf "${SKILLS_ARCHIVE}" -C "${SKILLS_DIR}" --strip-components=0 || {
            log_error "Skills 解压失败 (未知格式)"
            return 1
        }
    fi

    # 清理临时文件
    rm -f "${SKILLS_ARCHIVE}"

    log_success "Skills 部署完成"

    # 列出已安装的 Skills
    log_info "已安装的 Skills:"
    find "${SKILLS_DIR}" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | while read -r skill; do
        echo "  - ${skill}"
    done

    return 0
}

# 校验 Skills 依赖（支持 Python、Node.js、Go）
# 3. 从 Git 仓库部署 Skills（优先于 SKILLS_URL）
deploy_skills_from_git() {
    log_info "开始从 Git 仓库部署 Skills..."

    if [[ -z "${SKILLS_CONFIG}" ]] || [[ "${SKILLS_CONFIG}" == "[]" ]]; then
        log_warn "SKILLS_CONFIG 环境变量为空，跳过 Git Skills 部署"
        return 0
    fi

    # 校验 JSON 格式
    if ! echo "${SKILLS_CONFIG}" | jq empty 2>/dev/null; then
        log_error "SKILLS_CONFIG 不是有效的 JSON 格式"
        return 1
    fi

    local repo_url token skill_count
    repo_url=$(echo "${SKILLS_CONFIG}" | jq -r '.repoUrl // empty')
    token=$(echo "${SKILLS_CONFIG}" | jq -r '.token // empty')
    skill_count=$(echo "${SKILLS_CONFIG}" | jq -r '.skills | length')

    if [[ -z "${repo_url}" ]]; then
        log_error "SKILLS_CONFIG 中缺少 repoUrl"
        return 1
    fi

    if [[ "${skill_count}" == "null" ]] || [[ "${skill_count}" -eq 0 ]]; then
        log_warn "SKILLS_CONFIG 中 skills 列表为空"
        return 0
    fi

    # 构建认证 URL
    local clone_url="${repo_url}"
    if [[ -n "${token}" ]] && [[ "${repo_url}" == https://* ]]; then
        clone_url=$(echo "${repo_url}" | sed "s|https://|https://oauth2:${token}@|")
    fi

    log_info "共有 ${skill_count} 个 Skills 需要从 Git 部署"

    local success_count=0
    local fail_count=0

    for ((i=0; i<skill_count; i++)); do
        local skill_name skill_branch skill_commit
        skill_name=$(echo "${SKILLS_CONFIG}" | jq -r ".skills[$i].name")
        skill_branch=$(echo "${SKILLS_CONFIG}" | jq -r ".skills[$i].branch")
        skill_commit=$(echo "${SKILLS_CONFIG}" | jq -r ".skills[$i].commit // empty")

        log_info "[$((i+1))/${skill_count}] 部署 Skill: ${skill_name} (分支: ${skill_branch})"

        local tmp_dir="/tmp/skill-${skill_name}"
        local target_dir="${SKILLS_DIR}/${skill_name}"

        # 清理临时目录
        rm -rf "${tmp_dir}"

        # Clone 指定分支
        if ! git clone --depth 1 --single-branch -b "${skill_branch}" "${clone_url}" "${tmp_dir}" 2>&1; then
            log_error "Skill ${skill_name} clone 失败"
            ((fail_count++))
            continue
        fi

        # 如果指定了精确 commit，checkout 到该 commit
        if [[ -n "${skill_commit}" ]]; then
            (cd "${tmp_dir}" && git fetch --depth 1 origin "${skill_commit}" && git checkout "${skill_commit}") 2>&1 || {
                log_warn "Skill ${skill_name} checkout commit ${skill_commit} 失败，使用分支最新版本"
            }
        fi

        # 校验 SKILL.md 存在
        if [[ ! -f "${tmp_dir}/SKILL.md" ]]; then
            log_error "Skill ${skill_name} 缺少 SKILL.md 文件"
            rm -rf "${tmp_dir}"
            ((fail_count++))
            continue
        fi

        # 复制到目标目录（排除 .git）
        rm -rf "${target_dir}"
        mkdir -p "${target_dir}"
        if command_exists rsync; then
            rsync -a --exclude='.git' "${tmp_dir}/" "${target_dir}/"
        else
            cp -a "${tmp_dir}/." "${target_dir}/"
            rm -rf "${target_dir}/.git"
        fi

        # 清理临时目录
        rm -rf "${tmp_dir}"

        log_success "Skill ${skill_name} 部署成功"
        ((success_count++))
    done

    log_info "Git Skills 部署完成: 成功 ${success_count}，失败 ${fail_count}"

    if [[ ${fail_count} -gt 0 ]]; then
        return 1
    fi

    return 0
}

# 4. 校验 Skills 依赖（支持 Python、Node.js、Go）
check_skills_dependencies() {
    log_info "开始检查 Skills 依赖（支持: Python, Node.js, Go）..."

    if [[ ! -d "${SKILLS_DIR}" ]]; then
        log_warn "Skills 目录不存在，跳过依赖检查"
        return 0
    fi

    local skill_count=0
    local dep_issues=0

    for skill_dir in "${SKILLS_DIR}"/*/; do
        [[ -d "${skill_dir}" ]] || continue

        local skill_name
        skill_name=$(basename "${skill_dir}")
        ((skill_count++))

        local found_deps=false

        # === Python 依赖检查 ===
        local py_req_file=""
        if [[ -f "${skill_dir}requirements.txt" ]]; then
            py_req_file="${skill_dir}requirements.txt"
        elif [[ -f "${skill_dir}requirement.txt" ]]; then
            py_req_file="${skill_dir}requirement.txt"
        fi

        if [[ -n "${py_req_file}" ]]; then
            found_deps=true
            log_info "  [${skill_name}] Python 依赖: $(basename "${py_req_file}")"
            local py_issues
            py_issues=$(check_python_deps "${py_req_file}")
            if [[ "${py_issues}" == "FATAL" ]]; then
                return 1
            fi
            dep_issues=$((dep_issues + py_issues))
        fi

        # === Node.js 依赖检查 ===
        if [[ -f "${skill_dir}package.json" ]]; then
            found_deps=true
            log_info "  [${skill_name}] Node.js 依赖: package.json"
            local node_issues
            node_issues=$(check_nodejs_deps "${skill_dir}package.json")
            if [[ "${node_issues}" == "FATAL" ]]; then
                return 1
            fi
            dep_issues=$((dep_issues + node_issues))
        fi

        # === Go 依赖检查 ===
        if [[ -f "${skill_dir}go.mod" ]]; then
            found_deps=true
            log_info "  [${skill_name}] Go 依赖: go.mod"
            local go_issues
            go_issues=$(check_go_deps "${skill_dir}go.mod")
            if [[ "${go_issues}" == "FATAL" ]]; then
                return 1
            fi
            dep_issues=$((dep_issues + go_issues))
        fi

        if [[ "${found_deps}" == "false" ]]; then
            log_info "  [${skill_name}] 无依赖文件，跳过检查"
        fi
    done

    log_info "依赖检查完成: 扫描 ${skill_count} 个 Skills"

    if [[ ${dep_issues} -gt 0 ]]; then
        log_error "发现 ${dep_issues} 个依赖问题，构建失败"
        return 1
    fi

    log_success "所有依赖校验通过"
    return 0
}

# Python 依赖检查
check_python_deps() {
    local req_file="$1"
    local issues=0

    if ! python3.12 -m pip --version &> /dev/null; then
        log_error "    python3.12 -m pip 不可用，无法检查 Python 依赖"
        echo "FATAL"
        return
    fi

    while IFS= read -r line || [[ -n "$line" ]]; do
        # 跳过空行和注释
        [[ -z "${line}" ]] && continue
        [[ "${line}" =~ ^# ]] && continue
        [[ "${line}" =~ ^[[:space:]]*$ ]] && continue
        # 跳过 -r、-e 等特殊行
        [[ "${line}" =~ ^- ]] && continue

        # 解析包名（支持 package==1.0, package>=1.0 等格式）
        local pkg_name pkg_spec
        pkg_name=$(echo "${line}" | sed -E 's/([a-zA-Z0-9_-]+).*/\1/' | tr '[:upper:]' '[:lower:]' | tr '_' '-')
        pkg_spec="${line}"

        if python3.12 -m pip show "${pkg_name}" &> /dev/null; then
            local installed_version
            installed_version=$(python3.12 -m pip show "${pkg_name}" 2>/dev/null | grep "^Version:" | awk '{print $2}')

            if python3.12 -c "import pkg_resources; pkg_resources.require('${pkg_spec}')" 2>/dev/null; then
                log_success "    [Python][OK] ${pkg_name}==${installed_version}"
            else
                log_error "    [Python][VERSION] ${pkg_name}==${installed_version} 不满足版本要求: ${pkg_spec}"
                ((issues++))
            fi
        else
            log_error "    [Python][MISSING] ${pkg_name} 未安装 (需要: ${pkg_spec})"
            ((issues++))
        fi
    done < "${req_file}"

    echo ${issues}
}

# Node.js 依赖检查
check_nodejs_deps() {
    local pkg_json="$1"
    local issues=0

    # 检查 npm 是否可用
    if ! command -v npm &> /dev/null; then
        log_error "    npm 未安装，无法检查 Node.js 依赖"
        echo "FATAL"
        return
    fi

    # 提取 dependencies 和 devDependencies
    local deps
    deps=$(jq -r '(.dependencies // {}) + (.devDependencies // {}) | to_entries[] | "\(.key)|\(.value)"' "${pkg_json}" 2>/dev/null)

    if [[ -z "${deps}" ]]; then
        log_info "    package.json 无依赖声明"
        echo 0
        return
    fi

    while IFS='|' read -r pkg_name version_spec; do
        [[ -z "${pkg_name}" ]] && continue

        # 检查包是否全局安装
        local installed_version=""
        installed_version=$(npm list -g "${pkg_name}" --depth=0 2>/dev/null | grep "${pkg_name}@" | sed -E 's/.*@([0-9.]+).*/\1/' | head -1)

        if [[ -n "${installed_version}" ]]; then
            log_success "    [Node][OK] ${pkg_name}@${installed_version} (全局)"
        else
            # 检查是否在常见全局模块中
            if npm list -g --depth=0 2>/dev/null | grep -q "${pkg_name}"; then
                log_success "    [Node][OK] ${pkg_name} (全局已安装)"
            else
                log_error "    [Node][MISSING] ${pkg_name}@${version_spec} 未全局安装"
                ((issues++))
            fi
        fi
    done <<< "${deps}"

    echo ${issues}
}

# Go 依赖检查
check_go_deps() {
    local skill_dir="$1"
    local go_mod="$1"
    local issues=0

    # 检查 go 是否可用
    if ! command -v go &> /dev/null; then
        log_error "    go 未安装，无法检查 Go 依赖"
        echo "FATAL"
        return
    fi

    local go_mod="${skill_dir}/go.mod"
    if [[ ! -f "${go_mod}" ]]; then
        go_mod="${skill_dir}go.mod"
    fi

    local required_go_version
    required_go_version=$(grep "^go " "${go_mod}" | awk '{print $2}')

    if [[ -n "${required_go_version}" ]]; then
        local current_go_version
        current_go_version=$(go version | sed -E 's/go version go([0-9.]+).*/\1/')

        log_info "    Go 版本要求: ${required_go_version}, 当前版本: ${current_go_version}"

        # 简单版本比较（主版本.次版本）
        local req_major req_minor cur_major cur_minor
        req_major=$(echo "${required_go_version}" | cut -d. -f1)
        req_minor=$(echo "${required_go_version}" | cut -d. -f2)
        cur_major=$(echo "${current_go_version}" | cut -d. -f1)
        cur_minor=$(echo "${current_go_version}" | cut -d. -f2)

        if [[ "${cur_major}" -gt "${req_major}" ]] || \
           [[ "${cur_major}" -eq "${req_major}" && "${cur_minor}" -ge "${req_minor}" ]]; then
            log_success "    [Go][OK] Go 版本满足要求"
        else
            log_error "    [Go][VERSION] Go ${current_go_version} 不满足版本要求 (需要 >= ${required_go_version})"
            ((issues++))
        fi
    fi

    # 提取 require 块中的依赖
#    local in_require=false
#    while IFS= read -r line; do
#        if [[ "${line}" =~ ^require[[:space:]]*\( ]]; then
#            in_require=true
#            continue
#        fi
#        if [[ "${in_require}" == "true" && "${line}" =~ ^\) ]]; then
#            in_require=false
#            continue
#        fi
#        if [[ "${line}" =~ ^require[[:space:]]+ && ! "${line}" =~ \( ]]; then
#            local module_path module_version
#            module_path=$(echo "${line}" | awk '{print $2}')
#            module_version=$(echo "${line}" | awk '{print $3}')
#            log_info "    [Go][DEP] ${module_path} ${module_version}"
#            continue
#        fi
#        if [[ "${in_require}" == "true" ]]; then
#            line=$(echo "${line}" | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//')
#            [[ -z "${line}" ]] && continue
#            [[ "${line}" =~ ^// ]] && continue
#            local module_path module_version
#            module_path=$(echo "${line}" | awk '{print $1}')
#            module_version=$(echo "${line}" | awk '{print $2}')
#            if [[ -n "${module_path}" ]]; then
#                log_info "    [Go][DEP] ${module_path} ${module_version}"
#            fi
#        fi
#    done < "${go_mod}"

    log_info "    [Go] 依赖将在构建时通过 go mod download 自动获取"
    echo ${issues}
}

# 4. 部署 MCP 配置（优先 MCP_CONFIG 环境变量，其次 MCP_CONFIG_URL 下载）
deploy_mcp_config() {
    log_info "开始部署 MCP 配置..."

    # 优先级 1: MCP_CONFIG 环境变量（JSON 字符串，由构建系统从岗位配置生成）
    if [[ -n "${MCP_CONFIG}" ]] && [[ "${MCP_CONFIG}" != "[]" ]]; then
        log_info "检测到 MCP_CONFIG 环境变量，直接写入配置..."

        # 验证 JSON 格式
        if ! echo "${MCP_CONFIG}" | jq empty 2>/dev/null; then
            log_error "MCP_CONFIG 不是有效的 JSON 格式"
            return 1
        fi

        # 写入配置文件
        echo "${MCP_CONFIG}" | jq '.' > "${MCP_CONFIG_FILE}" || {
            log_error "写入 MCP 配置文件失败"
            return 1
        }

        # 设置权限
        chmod 0440 "${MCP_CONFIG_FILE}"
        chown root:agent "${MCP_CONFIG_FILE}" 2>/dev/null || true

        log_success "MCP 配置已从 MCP_CONFIG 环境变量部署到 ${MCP_CONFIG_FILE}"
        return 0
    fi

    # 优先级 2: MCP_CONFIG_URL 下载
    if [[ -n "${MCP_CONFIG_URL}" ]]; then
        log_info "从 ${MCP_CONFIG_URL} 下载 MCP 配置..."

        local tmp_mcp="/tmp/mcp_download.json"

        if ! curl -fsSL -o "${tmp_mcp}" "${MCP_CONFIG_URL}"; then
            log_error "MCP 配置下载失败"
            rm -f "${tmp_mcp}"
            return 1
        fi

        # 验证 JSON 格式
        if ! jq empty "${tmp_mcp}" 2>/dev/null; then
            log_error "下载的 MCP 配置不是有效的 JSON 格式"
            rm -f "${tmp_mcp}"
            return 1
        fi

        # 写入配置文件（格式化）
        jq '.' "${tmp_mcp}" > "${MCP_CONFIG_FILE}" || {
            log_error "写入 MCP 配置文件失败"
            rm -f "${tmp_mcp}"
            return 1
        }

        rm -f "${tmp_mcp}"
        chmod 0440 "${MCP_CONFIG_FILE}"
        chown root:agent "${MCP_CONFIG_FILE}" 2>/dev/null || true

        log_success "MCP 配置已从 URL 部署到 ${MCP_CONFIG_FILE}"
        return 0
    fi

    log_warn "MCP_CONFIG 和 MCP_CONFIG_URL 均为空，跳过 MCP 配置部署"
    return 0
}
# 部署 start 脚本和 ai_employee.py 到 /opt/agent/ (root:root, agent 不可改)
# 安全: 放在 /workspace 外防止 agent 篡改后 root 执行被劫持的脚本
deploy_start_scripts() {
    log_info "部署启动脚本到 /opt/agent/..."

    local deploy_dir="/opt/agent"
    mkdir -p "${deploy_dir}"
    local scripts=("start-single.sh" "start-dual.sh" "ai_employee.py")

    if [[ ! -d "${START_SCRIPTS_SRC}" ]]; then
        local existing=0
        for script in "${scripts[@]}"; do
            if [[ -f "${deploy_dir}/${script}" ]]; then
                chmod 0755 "${deploy_dir}/${script}" || true
                chown root:root "${deploy_dir}/${script}" || true
                ((existing++))
            fi
        done
        if [[ ${existing} -eq ${#scripts[@]} ]]; then
            log_warn "未找到 ${START_SCRIPTS_SRC}，使用基础镜像已存在的启动脚本"
            return 0
        fi

        log_error "启动脚本目录不存在: ${START_SCRIPTS_SRC}"
        log_error "请将 start-single.sh/start-dual.sh/ai_employee.py 放置到固定目录，或通过 START_SCRIPTS_SRC 指定路径"
        return 1
    fi

    local deployed=0

    for script in "${scripts[@]}"; do
        if [[ ! -f "${START_SCRIPTS_SRC}/${script}" ]]; then
            log_error "脚本不存在: ${START_SCRIPTS_SRC}/${script}"
            return 1
        fi

        cp "${START_SCRIPTS_SRC}/${script}" "${deploy_dir}/${script}"
        chmod 0755 "${deploy_dir}/${script}"
        chown root:root "${deploy_dir}/${script}"
        ((deployed++))
        log_info "  -> ${deploy_dir}/${script}"
    done

    log_success "启动脚本部署完成 (${deployed}/${#scripts[@]}, root:root 0755)"
    return 0
}

# 最终权限设置
finalize_permissions() {
    log_info "设置最终权限..."

    # workspace 归 agent + workspace 协作组，目录 setgid 保证跨容器共享组继承
    if id agent &>/dev/null; then
        chown -R agent:"${WORKSPACE_GROUP}" "${WORKSPACE_DIR}"
        chmod -R g+rwX "${WORKSPACE_DIR}"
        for dir in "${WORKSPACE_DIR}" "${WORKSPACE_DIR}/logs" "${WORKSPACE_DIR}/user" "${WORKSPACE_DIR}/workstation" "${WORKSPACE_DIR}/task-logs" "${WORKSPACE_DIR}/worker-logs"; do
            mkdir -p "${dir}"
            chown agent:"${WORKSPACE_GROUP}" "${dir}"
            chmod 2770 "${dir}"
        done
        log_info "  -> ${WORKSPACE_DIR} owner=agent:${WORKSPACE_GROUP}, 共享目录=2770(setgid)"

        # /opt/agent/ 下配置文件：root:agent 0440（agent 只读不可改）
        for cfg_file in "${SECURITY_FILE}" "${MCP_CONFIG_FILE}" "${AGENT_CONFIG_DIR}/config.json"; do
            if [[ -f "${cfg_file}" ]]; then
                chmod 0440 "${cfg_file}"
                chown root:agent "${cfg_file}"
            fi
        done
        log_info "  -> config/security/mcp.json → 0440 root:agent"

        # skills 目录：root:agent 0750（agent 可读可进入，不可改）
        if [[ -d "${SKILLS_DIR}" ]]; then
            chown -R root:agent "${SKILLS_DIR}"
            find "${SKILLS_DIR}" -type d -exec chmod 0750 {} \;
            find "${SKILLS_DIR}" -type f -exec chmod 0440 {} \;
        fi
        log_info "  -> skills/ → 0750/0440 root:agent"

        log_success "workspace + /opt/agent/ 权限设置完成"
    else
        log_warn "agent 用户不存在，跳过权限设置"
    fi

    # /etc/zzd 加固校验 — monitor(zzd) 可读, agent 不可读不可改
    if [[ -d "${ZZD_CONFIG_DIR}" ]]; then
        find "${ZZD_CONFIG_DIR}" -type d -exec chmod 0750 {} \;
        find "${ZZD_CONFIG_DIR}" -type f -exec chmod 0440 {} \;
        chown -R root:monitor "${ZZD_CONFIG_DIR}"
        # socket 和 audit 目录归 monitor（zzd 进程需要写入）
        if [[ -d "${ZZD_SOCKET_DIR}" ]]; then
            chown monitor:monitor "${ZZD_SOCKET_DIR}"
            chmod 0755 "${ZZD_SOCKET_DIR}"
        fi
        if [[ -d "${ZZD_AUDIT_DIR}" ]]; then
            chown monitor:monitor "${ZZD_AUDIT_DIR}"
            chmod 0700 "${ZZD_AUDIT_DIR}"
        fi
        log_success "/etc/zzd 权限加固完成 (config: 0750/0440 root:monitor; socket/audit: monitor:monitor)"
    fi

    return 0
}

# =============================================================================
# Phase 7: 网络隔离 (iptables)
# =============================================================================

# 配置 iptables 网络白名单
# Gateway 模式下: MCP 访问全部走 Gateway (ClusterIP)，不需要逐个开放 MCP 端点
# 仅放行: loopback / DNS / RFC1918 / 运行时环境变量端点 / Skills 端点
# 策略: 使用自定义链 AGENT_OUTPUT，不影响其他用户/进程的网络访问
setup_network_firewall() {
    log_info "配置 iptables 网络白名单..."

    if ! command_exists iptables; then
        log_warn "iptables 未安装，跳过网络隔离配置"
        return 0
    fi

    local allowed_hosts=()

    # 从 Skills 中提取可能声明的外部端点
    if [[ -d "${SKILLS_DIR}" ]]; then
        for skill_meta in "${SKILLS_DIR}"/*/SKILL.md; do
            [[ -f "${skill_meta}" ]] || continue
            while IFS= read -r endpoint_host; do
                [[ -n "${endpoint_host}" ]] && allowed_hosts+=("${endpoint_host}")
            done < <(grep -oP 'https?://\K[^/:]+' "${skill_meta}" 2>/dev/null | sort -u)
        done
    fi

    # 去重
    local unique_hosts=()
    local seen_map=""
    for h in "${allowed_hosts[@]}"; do
        if [[ "${seen_map}" != *"|${h}|"* ]]; then
            unique_hosts+=("${h}")
            seen_map="${seen_map}|${h}|"
        fi
    done

    local agent_uid=1001

    local fw_script="/opt/agent/setup-firewall.sh"

    cat > "${fw_script}" <<'FWEOF'
#!/bin/bash
# Agent 网络隔离防火墙规则 (自动生成，请勿手动编辑)
# MCP 访问已统一通过 Gateway (K8s ClusterIP)，无需逐个开放 MCP 端点
set -e

AGENT_UID=1001

iptables -N AGENT_OUTPUT 2>/dev/null || iptables -F AGENT_OUTPUT

iptables -D OUTPUT -m owner --uid-owner ${AGENT_UID} -j AGENT_OUTPUT 2>/dev/null || true
iptables -A OUTPUT -m owner --uid-owner ${AGENT_UID} -j AGENT_OUTPUT

# ── 基础规则 ──
iptables -A AGENT_OUTPUT -o lo -j ACCEPT
iptables -A AGENT_OUTPUT -m state --state ESTABLISHED,RELATED -j ACCEPT

# ── DNS (UDP/TCP 53) ──
iptables -A AGENT_OUTPUT -p udp --dport 53 -j ACCEPT
iptables -A AGENT_OUTPUT -p tcp --dport 53 -j ACCEPT

# ── RFC1918 私有网络: 集群内部通信 (Redis, K8s Service, Pod CIDR, MCP Gateway) ──
iptables -A AGENT_OUTPUT -d 10.0.0.0/8 -j ACCEPT
iptables -A AGENT_OUTPUT -d 172.16.0.0/12 -j ACCEPT
iptables -A AGENT_OUTPUT -d 192.168.0.0/16 -j ACCEPT

# ── 运行时服务端点 (从环境变量动态提取公网 host) ──
extract_host() {
    local url="$1"
    echo "${url}" | sed -E 's|^[a-zA-Z]+://||; s|/.*||; s|:[0-9]+$||'
}

for env_url in "${REDIS_URL:-}" "${API_BASE_URL:-}" "${WS_BASE_URL:-}" "${LLM_GATEWAY_URL:-}" "${MAIN_PY_URL:-}"; do
    [ -z "${env_url}" ] && continue
    local_host=$(extract_host "${env_url}")
    [ -z "${local_host}" ] && continue
    case "${local_host}" in
        10.*|172.1[6-9].*|172.2[0-9].*|172.3[0-1].*|192.168.*|*.svc|*.svc.*|localhost) continue ;;
    esac
    iptables -A AGENT_OUTPUT -d "${local_host}" -j ACCEPT
done

FWEOF

    # 追加 Skills 白名单
    if [[ ${#unique_hosts[@]} -gt 0 ]]; then
        echo "# ── Skills 白名单 (构建时静态) ──" >> "${fw_script}"
        for host in "${unique_hosts[@]}"; do
            echo "iptables -A AGENT_OUTPUT -d \"${host}\" -j ACCEPT" >> "${fw_script}"
        done
        echo "" >> "${fw_script}"
    fi

    cat >> "${fw_script}" <<'FWEOF2'
# ── 默认拒绝: agent 用户的所有其他出站连接 ──
iptables -A AGENT_OUTPUT -j REJECT --reject-with icmp-port-unreachable

echo "[INFO] Agent 网络隔离防火墙已生效 (agent uid=${AGENT_UID})"
FWEOF2

    chmod 0700 "${fw_script}"
    chown root:root "${fw_script}"

    if [[ ${#unique_hosts[@]} -gt 0 ]]; then
        log_info "白名单主机 (${#unique_hosts[@]}): ${unique_hosts[*]}"
    else
        log_info "MCP 访问通过 Gateway (K8s ClusterIP)，无需额外白名单"
    fi

    log_info "防火墙规则脚本已生成: ${fw_script}"

    if bash "${fw_script}" 2>/dev/null; then
        log_success "iptables 网络白名单已在构建环境生效"
    else
        log_warn "构建环境无法应用 iptables（可能缺少 NET_ADMIN 权限），规则已保存到 ${fw_script}，需在运行时由 start.sh 加载"
    fi

    return 0
}

# =============================================================================
# 主函数
# =============================================================================
main() {
    log_info "=========================================="
    log_info "K8s Agent 镜像构建脚本启动（生产版本 v2）"
    log_info "参考: docs/docker.md §3.2"
    log_info "=========================================="

    local exit_code=0

    log_info "构建输入目录解析结果:"
    log_info "  BUILD_ASSETS_ROOT=${BUILD_ASSETS_ROOT}"
    log_info "  ZZD_BIN_SRC=${ZZD_BIN_SRC}"
    log_info "  SDK_SRC=${SDK_SRC}"
    log_info "  START_SCRIPTS_SRC=${START_SCRIPTS_SRC}"

    # ── Phase 1: 系统依赖 ──
    if ! check_prerequisites; then
        log_error "前置条件检查失败"
        exit 1
    fi

    # 创建目录结构
    if ! create_directories; then
        log_error "目录创建失败"
        exit 1
    fi

    # ── Phase 2: zzd + SDK + 用户 ──
    if ! install_zzd_binaries; then
        log_error "zzd 二进制安装失败"
        exit_code=1
    fi

    if ! install_sdk; then
        log_error "SDK 安装失败"
        exit 1
    fi

    if ! patch_sdk_runtime_model_override; then
        log_error "SDK runtime model 补丁失败"
        exit 1
    fi

    if ! setup_agent_user; then
        log_error "agent 用户创建失败"
        exit 1
    fi

    # ── Phase 3: workspace + zzd 配置 ──
    if ! setup_workspace; then
        log_error "workspace 目录结构创建失败"
        exit 1
    fi

    if ! setup_agent_config; then
        log_error "agent 配置部署失败"
        exit 1
    fi

    if ! download_cedar_policies; then
        log_error "Cedar 策略下载/部署失败"
        exit_code=1
    fi

    if ! setup_zzd_directories; then
        log_error "zzd 目录创建失败"
        exit 1
    fi

    # ── Phase 4: Git ──
    if ! install_git; then
        log_error "git 安装失败"
        exit 1
    fi

    if ! clone_git_repos; then
        log_error "Git 仓库克隆失败"
        exit_code=1
    fi

    # 2 & 3. 部署 Skills（优先 SKILLS_CONFIG Git clone，其次 SKILLS_URL 下载）
    if [[ -n "${SKILLS_CONFIG}" ]] && [[ "${SKILLS_CONFIG}" != "[]" ]]; then
        if ! deploy_skills_from_git; then
            log_error "Skills Git 部署失败"
            exit_code=1
        fi
    elif [[ -n "${SKILLS_URL}" ]]; then
        if ! download_skills; then
            log_error "Skills 下载失败"
            exit_code=1
        fi
    else
        log_info "未配置 Skills（SKILLS_CONFIG 和 SKILLS_URL 均为空），跳过"
    fi

    # 3. 检查依赖（失败则终止构建）
    if ! check_skills_dependencies; then
        log_error "Skills 依赖检查失败"
        exit 1
    fi

    # ── Phase 6: 启动脚本 + 权限 ──
    if ! deploy_start_scripts; then
        log_error "启动脚本部署失败"
        exit_code=1
    fi

    if ! finalize_permissions; then
        log_error "权限设置失败"
        exit_code=1
    fi

    # 5. 部署 MCP 配置
    if ! deploy_mcp_config; then
        log_error "MCP 配置部署失败"
        exit_code=1
    fi

    # ── Phase 7: 网络隔离 (生成防火墙脚本，运行时由 ENABLE_NETWORK_FIREWALL 控制是否加载) ──
    if ! setup_network_firewall; then
        log_error "网络隔离配置失败"
        exit_code=1
    fi

    # ── 清理构建临时文件（避免策略/源码残留在镜像中被 agent 读取）──
    log_info "清理构建临时文件..."
    rm -rf /tmp/cedar-policies /tmp/cedar-policies-download

    # 仅清理 /tmp 回退路径，避免误删固定资产目录（如 /opt/linkwork-agent-build）
    for cleanup_dir in "${ZZD_BIN_SRC}" "${SDK_SRC}" "${START_SCRIPTS_SRC}"; do
        if [[ "${cleanup_dir}" == /tmp/* ]]; then
            rm -rf "${cleanup_dir}"
        fi
    done

    log_success "构建临时文件已清理"

    # ── 输出结果 ──
    log_info "=========================================="
    if [[ ${exit_code} -eq 0 ]]; then
        log_success "构建完成"
    else
        log_error "构建完成但存在错误"
    fi
    log_info "=========================================="

    # 输出目录结构
    log_info "工作目录结构:"
    if command -v tree &> /dev/null; then
        tree -L 3 "${WORKSPACE_DIR}" 2>/dev/null || ls -laR "${WORKSPACE_DIR}"
    else
        ls -laR "${WORKSPACE_DIR}" 2>/dev/null | head -40
    fi

    log_info "zzd 配置目录:"
    ls -la "${ZZD_CONFIG_DIR}" 2>/dev/null || true
    ls -la "${ZZD_POLICY_DIR}" 2>/dev/null || true

    log_info "Agent 组件目录:"
    if command -v tree &> /dev/null; then
        tree -L 2 /opt/agent/ 2>/dev/null || ls -la /opt/agent/
    else
        ls -la /opt/agent/ 2>/dev/null || true
    fi

    exit ${exit_code}
}

# 执行主函数
main "$@"
