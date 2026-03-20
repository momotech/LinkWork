package com.linkwork.service;

import com.linkwork.model.dto.MergedConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Docker Compose 构建包生成器
 *
 * 仅支持 Alone 模式，生成用户可本地构建和启动的 docker-compose.yaml。
 * 镜像由用户在本地通过 docker compose up --build 构建，无需服务端推送。
 */
@Component
@Slf4j
public class DockerComposeGenerator {

    /**
     * 生成 docker-compose.yaml 内容
     *
     * @param config 融合后的配置
     * @return docker-compose.yaml 字符串
     */
    public String generateComposeYaml(MergedConfig config) {
        log.info("Generating Docker Compose YAML for service {}", config.getServiceId());

        StringBuilder yaml = new StringBuilder();
        yaml.append(generateHeader(config));
        yaml.append(generateServices(config));
        yaml.append(generateVolumes());
        return yaml.toString();
    }

    private String generateHeader(MergedConfig config) {
        return String.format("""
# docker-compose.yaml - AI Worker Service
# Service ID: %s
# User ID: %s
#
# 使用方式:
#   1. 首次启动（构建镜像 + 启动容器）:
#      docker compose up --build -d
#   2. 查看日志:
#      docker compose logs -f agent
#   3. 停止服务:
#      docker compose down
#   4. 后续启动（已构建过镜像，无需重新构建）:
#      docker compose up -d
#
# 注意:
#   - 首次构建需要拉取基础镜像和安装依赖，耗时较长（约 5-10 分钟）
#   - 需要能访问 docker.momo.com 拉取基础镜像
#   - 需要能访问 git.wemomo.com 克隆 SDK 仓库

""", config.getServiceId(), config.getUserId());
    }

    private String generateServices(MergedConfig config) {
        String workstationId = config.getWorkstationId() != null
                ? config.getWorkstationId() : config.getServiceId();

        StringBuilder sb = new StringBuilder();
        sb.append("services:\n");
        sb.append("  agent:\n");
        sb.append("    build:\n");
        sb.append("      context: .\n");
        sb.append("      dockerfile: Dockerfile\n");
        sb.append(String.format("    image: ai-worker-%s:latest\n", config.getServiceId()));
        sb.append(String.format("    container_name: ai-worker-%s\n", config.getServiceId()));
        sb.append("    user: root\n");
        sb.append("    command: [\"/opt/agent/start-single.sh\"]\n");
        sb.append("    environment:\n");

        appendEnv(sb, "WORKSTATION_ID", workstationId);
        appendEnv(sb, "REDIS_URL", config.getRedisUrl());
        appendEnv(sb, "CONFIG_FILE", "/opt/agent/config.json");
        appendEnv(sb, "IDLE_TIMEOUT", "86400");
        appendEnv(sb, "SERVICE_ID", config.getServiceId());
        appendEnv(sb, "USER_ID", config.getUserId());
        appendEnv(sb, "API_BASE_URL", config.getApiBaseUrl());
        appendEnv(sb, "WS_BASE_URL", config.getWsBaseUrl());
        appendEnv(sb, "LLM_GATEWAY_URL", config.getLlmGatewayUrl());
        if (config.getRoleId() != null) {
            appendEnv(sb, "ROLE_ID", String.valueOf(config.getRoleId()));
        }

        sb.append("    volumes:\n");
        sb.append("      - workspace:/workspace\n");
        sb.append("    restart: \"no\"\n");
        sb.append("    deploy:\n");
        sb.append("      resources:\n");
        sb.append("        limits:\n");
        sb.append(String.format("          cpus: '%s'\n", config.getAgentResources().getCpuLimit()));
        sb.append(String.format("          memory: %s\n", config.getAgentResources().getMemoryLimit()));
        sb.append("        reservations:\n");
        sb.append(String.format("          cpus: '%s'\n", config.getAgentResources().getCpuRequest()));
        sb.append(String.format("          memory: %s\n", config.getAgentResources().getMemoryRequest()));

        return sb.toString();
    }

    private void appendEnv(StringBuilder sb, String key, String value) {
        if (StringUtils.hasText(value)) {
            sb.append(String.format("      - %s=%s\n", key, value));
        }
    }

    private String generateVolumes() {
        return """

volumes:
  workspace:
""";
    }
}
