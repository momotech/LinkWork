#!/usr/bin/env bash
set -euo pipefail

BACKEND_CONTAINER="${BACKEND_CONTAINER:-linkwork-backend}"
BACKEND_IMAGE="${BACKEND_IMAGE:-b98fa55d8251}"
BACKEND_JAR_PATH="${BACKEND_JAR_PATH:-/home/li.zhiwen/linkwork-0.1.0-SNAPSHOT.jar}"
KUBECONFIG_PATH_HOST="${KUBECONFIG_PATH_HOST:-/home/li.zhiwen/.kube/config}"

BACKEND_HOST_PORT="${BACKEND_HOST_PORT:-18082}"
BACKEND_SERVER_PORT="${BACKEND_SERVER_PORT:-8081}"
BACKEND_JAVA_CMD="${BACKEND_JAVA_CMD:-java -jar /app/app.jar --spring.profiles.active=gg --server.port=${BACKEND_SERVER_PORT}}"
BACKEND_CONTAINER_PORT="${BACKEND_CONTAINER_PORT:-8081}"
DEFAULT_BRIDGE_GATEWAY_IP="$(docker network inspect bridge -f '{{(index .IPAM.Config 0).Gateway}}' 2>/dev/null || true)"
HOST_GATEWAY_IP="${HOST_GATEWAY_IP:-${DEFAULT_BRIDGE_GATEWAY_IP:-192.168.100.1}}"

MCP_PROXY_CONTAINER="${MCP_PROXY_CONTAINER:-linkwork-mcp-gateway-proxy}"
MCP_PROXY_CONF="${MCP_PROXY_CONF:-/tmp/linkwork-mcp-gateway-proxy.conf}"
MCP_GATEWAY_NODE_IP="${MCP_GATEWAY_NODE_IP:-192.168.0.2}"
MCP_GATEWAY_NODE_PORT="${MCP_GATEWAY_NODE_PORT:-30890}"
MCP_PROXY_PORT="${MCP_PROXY_PORT:-38090}"

DB_URL="${DB_URL:-jdbc:mysql://${HOST_GATEWAY_IP}:3307/linkwork?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-robot123}"
BACKEND_REDIS_HOST="${BACKEND_REDIS_HOST:-${HOST_GATEWAY_IP}}"
BACKEND_REDIS_PORT="${BACKEND_REDIS_PORT:-6380}"
BACKEND_REDIS_PASSWORD="${BACKEND_REDIS_PASSWORD:-}"
REDIS_CONTAINER="${REDIS_CONTAINER:-momo-redis}"
REDIS_CONTAINER_PORT="${REDIS_CONTAINER_PORT:-6379}"

SANDBOX_PROVIDER="${SANDBOX_PROVIDER:-k8s-volcano}"
K8S_NAMESPACE="${K8S_NAMESPACE:-linkwork-dev}"
BUILD_PUSH_ENABLED="${BUILD_PUSH_ENABLED:-false}"
BUILD_LOCAL_LOAD_ENABLED="${BUILD_LOCAL_LOAD_ENABLED:-true}"
BUILD_KIND_CLUSTER_NAME="${BUILD_KIND_CLUSTER_NAME:-shared-dev}"
BUILD_DEFAULT_AGENT_BASE_IMAGE="${BUILD_DEFAULT_AGENT_BASE_IMAGE:-10.30.107.146/robot/rockylinux9-agent:v1.3}"
K8S_DEFAULT_AGENT_IMAGE="${K8S_DEFAULT_AGENT_IMAGE:-10.30.107.146/robot/rockylinux9-agent:v1.3}"
K8S_DEFAULT_RUNNER_IMAGE="${K8S_DEFAULT_RUNNER_IMAGE:-10.30.107.146/robot/rockylinux9-agent:v1.3}"
K8S_AUTO_CREATE_IMAGE_PULL_SECRET="${K8S_AUTO_CREATE_IMAGE_PULL_SECRET:-false}"

MCP_PROXY_BASE_URL="${MCP_PROXY_BASE_URL:-http://${HOST_GATEWAY_IP}:${MCP_PROXY_PORT}}"
MCP_AGENT_BASE_URL="${MCP_AGENT_BASE_URL:-http://linkwork-mcp-gateway.linkwork-mcp-gateway.svc.cluster.local:9080}"
KIND_NETWORK="${KIND_NETWORK:-kind}"
MCP_GATEWAY_NAMESPACE="${MCP_GATEWAY_NAMESPACE:-linkwork-mcp-gateway}"
MCP_GATEWAY_DEPLOYMENT="${MCP_GATEWAY_DEPLOYMENT:-linkwork-mcp-gateway}"

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"
}

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    log "ERROR: file not found: $path"
    exit 1
  fi
}

container_exists() {
  local name="$1"
  docker ps -a --format '{{.Names}}' | grep -Fxq "${name}"
}

ensure_container_exists() {
  local name="$1"
  if ! container_exists "${name}"; then
    log "ERROR: container not found: ${name}"
    exit 1
  fi
}

connect_to_kind_network() {
  local container="$1"
  docker network connect "${KIND_NETWORK}" "${container}" >/dev/null 2>&1 || true
}

kind_ip_of_container() {
  local container="$1"
  docker inspect -f "{{with index .NetworkSettings.Networks \"${KIND_NETWORK}\"}}{{.IPAddress}}{{end}}" "${container}"
}

write_proxy_conf() {
  cat >"${MCP_PROXY_CONF}" <<CONF
worker_processes 1;
error_log /var/log/nginx/error.log warn;
pid /tmp/nginx.pid;

events { worker_connections 1024; }

http {
  access_log /var/log/nginx/access.log;
  server {
    listen ${MCP_PROXY_PORT};
    location / {
      proxy_pass http://${MCP_GATEWAY_NODE_IP}:${MCP_GATEWAY_NODE_PORT};
      proxy_http_version 1.1;
      proxy_set_header Host \$host;
      proxy_set_header X-Real-IP \$remote_addr;
      proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto \$scheme;
    }
  }
}
CONF
}

start_gateway_proxy() {
  log "Starting gateway proxy container: ${MCP_PROXY_CONTAINER}"
  docker rm -f "${MCP_PROXY_CONTAINER}" >/dev/null 2>&1 || true
  docker run -d \
    --name "${MCP_PROXY_CONTAINER}" \
    --restart unless-stopped \
    --network host \
    -v "${MCP_PROXY_CONF}:/etc/nginx/nginx.conf:ro" \
    nginx:1.27-alpine >/dev/null
}

wait_gateway_proxy() {
  local url="http://127.0.0.1:${MCP_PROXY_PORT}/healthz"
  for _ in $(seq 1 20); do
    if curl -fsS --connect-timeout 2 "${url}" >/dev/null; then
      log "Gateway proxy is ready: ${url}"
      return 0
    fi
    sleep 1
  done
  log "ERROR: gateway proxy health check failed: ${url}"
  docker logs "${MCP_PROXY_CONTAINER}" --tail 80 || true
  exit 1
}

deploy_backend() {
  log "Restarting backend container: ${BACKEND_CONTAINER}"
  docker rm -f "${BACKEND_CONTAINER}" >/dev/null 2>&1 || true

  docker run -d \
    --name "${BACKEND_CONTAINER}" \
    --restart unless-stopped \
    -p "${BACKEND_HOST_PORT}:${BACKEND_SERVER_PORT}" \
    --add-host host.docker.internal:host-gateway \
    -v "${BACKEND_JAR_PATH}:/app/app.jar:ro" \
    -v "${KUBECONFIG_PATH_HOST}:/root/.kube/config:ro" \
    -v /usr/bin/docker:/usr/bin/docker:ro \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -e SPRING_PROFILES_ACTIVE=gg \
    -e SERVER_PORT="${BACKEND_SERVER_PORT}" \
    -e LINKWORK_DB_URL="${DB_URL}" \
    -e LINKWORK_DB_USERNAME="${DB_USERNAME}" \
    -e LINKWORK_DB_PASSWORD="${DB_PASSWORD}" \
    -e LINKWORK_REDIS_HOST="${BACKEND_REDIS_HOST}" \
    -e LINKWORK_REDIS_PORT="${BACKEND_REDIS_PORT}" \
    -e LINKWORK_REDIS_PASSWORD="${BACKEND_REDIS_PASSWORD}" \
    -e LINKWORK_SANDBOX_PROVIDER="${SANDBOX_PROVIDER}" \
    -e KUBECONFIG_PATH=/root/.kube/config \
    -e K8S_NAMESPACE="${K8S_NAMESPACE}" \
    -e LINKWORK_BUILD_PUSH_ENABLED="${BUILD_PUSH_ENABLED}" \
    -e LINKWORK_BUILD_LOCAL_LOAD_ENABLED="${BUILD_LOCAL_LOAD_ENABLED}" \
    -e LINKWORK_BUILD_KIND_CLUSTER_NAME="${BUILD_KIND_CLUSTER_NAME}" \
    -e LINKWORK_BUILD_DEFAULT_AGENT_BASE_IMAGE="${BUILD_DEFAULT_AGENT_BASE_IMAGE}" \
    -e LINKWORK_K8S_DEFAULT_AGENT_IMAGE="${K8S_DEFAULT_AGENT_IMAGE}" \
    -e LINKWORK_K8S_DEFAULT_RUNNER_IMAGE="${K8S_DEFAULT_RUNNER_IMAGE}" \
    -e LINKWORK_K8S_AUTO_CREATE_IMAGE_PULL_SECRET="${K8S_AUTO_CREATE_IMAGE_PULL_SECRET}" \
    -e LINKWORK_AGENT_MCP_ENABLED=true \
    -e LINKWORK_AGENT_MCP_MODE=gateway \
    -e LINKWORK_AGENT_MCP_GATEWAY_PROXY_BASE_URL="${MCP_PROXY_BASE_URL}" \
    -e LINKWORK_AGENT_MCP_GATEWAY_AGENT_BASE_URL="${MCP_AGENT_BASE_URL}" \
    "${BACKEND_IMAGE}" \
    sh -lc "${BACKEND_JAVA_CMD}" >/dev/null
}

configure_gateway_env() {
  ensure_container_exists "${BACKEND_CONTAINER}"
  ensure_container_exists "${REDIS_CONTAINER}"

  connect_to_kind_network "${BACKEND_CONTAINER}"
  connect_to_kind_network "${REDIS_CONTAINER}"

  local backend_kind_ip
  local redis_kind_ip
  local web_service_base_url
  local redis_addr

  backend_kind_ip="$(kind_ip_of_container "${BACKEND_CONTAINER}")"
  redis_kind_ip="$(kind_ip_of_container "${REDIS_CONTAINER}")"

  if [[ -z "${backend_kind_ip}" || -z "${redis_kind_ip}" ]]; then
    log "ERROR: failed to resolve kind-network IPs, backend='${backend_kind_ip}', redis='${redis_kind_ip}'"
    exit 1
  fi

  web_service_base_url="http://${backend_kind_ip}:${BACKEND_CONTAINER_PORT}"
  redis_addr="${redis_kind_ip}:${REDIS_CONTAINER_PORT}"

  log "Configuring gateway deployment env"
  kubectl -n "${MCP_GATEWAY_NAMESPACE}" set env "deployment/${MCP_GATEWAY_DEPLOYMENT}" \
    "WEB_SERVICE_BASE_URL=${web_service_base_url}" \
    "REDIS_ADDR=${redis_addr}" >/dev/null
  kubectl -n "${MCP_GATEWAY_NAMESPACE}" rollout status "deployment/${MCP_GATEWAY_DEPLOYMENT}" --timeout=120s >/dev/null

  log "Gateway env applied: WEB_SERVICE_BASE_URL=${web_service_base_url}, REDIS_ADDR=${redis_addr}"
}

verify_backend() {
  local proxy_url="${MCP_PROXY_BASE_URL}/healthz"
  local app_url="http://127.0.0.1:${BACKEND_HOST_PORT}/api/v1/mcp-servers/health"
  local node_gateway_url="http://${MCP_GATEWAY_NODE_IP}:${MCP_GATEWAY_NODE_PORT}/healthz"
  local status_code=""

  for _ in $(seq 1 40); do
    if [[ "$(docker inspect "${BACKEND_CONTAINER}" --format '{{.State.Status}}' 2>/dev/null || true)" == "running" ]]; then
      status_code="$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 2 "${app_url}" || true)"
      if [[ "${status_code}" != "000" ]]; then
        break
      fi
    fi
    sleep 1
  done

  if [[ "${status_code}" == "000" ]]; then
    log "ERROR: backend port not reachable: ${app_url}"
    docker ps -a --filter "name=${BACKEND_CONTAINER}"
    docker logs "${BACKEND_CONTAINER}" --tail 120 || true
    exit 1
  fi

  log "Checking gateway path from backend container"
  for _ in $(seq 1 20); do
    if docker exec "${BACKEND_CONTAINER}" sh -lc "curl -fsS --connect-timeout 3 --max-time 8 '${proxy_url}' >/dev/null"; then
      break
    fi
    sleep 1
  done

  if ! docker exec "${BACKEND_CONTAINER}" sh -lc "curl -fsS --connect-timeout 3 --max-time 8 '${proxy_url}' >/dev/null"; then
    log "ERROR: backend cannot reach gateway proxy: ${proxy_url}"
    docker logs "${MCP_PROXY_CONTAINER}" --tail 80 || true
    exit 1
  fi

  log "Backend port is reachable with status code: ${status_code}"
  log "Checking nodeport gateway health"
  curl -fsS --connect-timeout 3 "${node_gateway_url}" >/dev/null

  log "Backend env snapshot"
  docker inspect "${BACKEND_CONTAINER}" --format '{{range .Config.Env}}{{println .}}{{end}}' | sort | grep -E 'LINKWORK_AGENT_MCP|LINKWORK_MCP_MODE|MCP_PROXY_BASE_URL|MCP_GATEWAY_AGENT_BASE_URL|SERVER_PORT|K8S_NAMESPACE|KUBECONFIG_PATH|LINKWORK_DB_URL' || true

  log "Done. backend=http://<gg-host>:${BACKEND_HOST_PORT}, mcp-proxy=${MCP_PROXY_BASE_URL}"
}

main() {
  require_file "${BACKEND_JAR_PATH}"
  require_file "${KUBECONFIG_PATH_HOST}"
  write_proxy_conf
  start_gateway_proxy
  wait_gateway_proxy
  deploy_backend
  configure_gateway_env
  verify_backend
}

main "$@"
