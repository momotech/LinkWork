package com.linkwork.service;

import com.linkwork.agent.mcp.core.McpClient;
import com.linkwork.agent.mcp.core.model.McpEndpoint;
import com.linkwork.agent.mcp.core.model.McpProbeResponse;
import com.linkwork.model.dto.McpProbeResult;
import com.linkwork.model.entity.McpServerEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 健康检查组件（基于 linkwork-mcp-starter）。
 */
@Component
@Slf4j
public class McpHealthChecker {

    private final McpServerService mcpServerService;
    private final McpClient mcpClient;
    private final RestTemplate healthRestTemplate;

    public McpHealthChecker(McpServerService mcpServerService, McpClient mcpClient) {
        this.mcpServerService = mcpServerService;
        this.mcpClient = mcpClient;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.healthRestTemplate = new RestTemplate(factory);
    }

    @Scheduled(fixedRate = 30_000)
    public void healthCheckAll() {
        try {
            List<McpServerEntity> servers = mcpServerService.listByTypes(List.of("http", "sse"));
            if (servers.isEmpty()) {
                return;
            }
            for (McpServerEntity server : servers) {
                checkSingle(server);
            }
        } catch (Exception e) {
            log.error("MCP health check cycle failed unexpectedly", e);
        }
    }

    public McpProbeResult probeSingle(McpServerEntity server) {
        String probeUrl = resolveProbeUrl(server);
        if (!StringUtils.hasText(probeUrl)) {
            return McpProbeResult.builder()
                    .status("offline")
                    .latencyMs(0)
                    .message("No probe URL configured")
                    .probeUrl(null)
                    .build();
        }

        if (hasDedicatedHealthCheckUrl(server)) {
            return probeByHttp(server, probeUrl);
        }
        return probeByMcpClient(server, probeUrl);
    }

    private void checkSingle(McpServerEntity server) {
        McpProbeResult result = probeSingle(server);

        if ("online".equals(result.getStatus()) || "degraded".equals(result.getStatus())) {
            int consecutiveFailures = "online".equals(result.getStatus())
                    ? 0
                    : (server.getConsecutiveFailures() != null ? server.getConsecutiveFailures() + 1 : 1);
            mcpServerService.updateHealth(server.getId(), result.getStatus(), result.getLatencyMs(),
                    result.getMessage(), consecutiveFailures);
            return;
        }

        handleFailure(server, result.getMessage(), result.getLatencyMs());
    }

    private McpProbeResult probeByMcpClient(McpServerEntity server, String probeUrl) {
        long start = System.currentTimeMillis();
        try {
            McpEndpoint endpoint = toEndpoint(server, probeUrl);
            McpProbeResponse response = mcpClient.probe(endpoint);
            int latencyMs = response != null && response.getLatencyMs() > 0
                    ? response.getLatencyMs()
                    : (int) Math.max(1, System.currentTimeMillis() - start);

            if (response != null && response.isSuccess()) {
                String status = latencyMs < 2000 ? "online" : "degraded";
                return McpProbeResult.builder()
                        .status(status)
                        .latencyMs(latencyMs)
                        .message("MCP OK (" + latencyMs + "ms)")
                        .probeUrl(probeUrl)
                        .build();
            }

            return McpProbeResult.builder()
                    .status("offline")
                    .latencyMs(latencyMs)
                    .message(response == null ? "mcp probe failed" : response.getMessage())
                    .probeUrl(probeUrl)
                    .build();
        } catch (Exception e) {
            int latencyMs = (int) Math.max(1, System.currentTimeMillis() - start);
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (error.length() > 250) {
                error = error.substring(0, 250);
            }
            return McpProbeResult.builder()
                    .status("offline")
                    .latencyMs(latencyMs)
                    .message(error)
                    .probeUrl(probeUrl)
                    .build();
        }
    }

    /**
     * 兼容 dedicated healthCheckUrl（普通 HTTP 健康检查）。
     */
    private McpProbeResult probeByHttp(McpServerEntity server, String probeUrl) {
        long start = System.currentTimeMillis();
        try {
            HttpMethod method = "sse".equalsIgnoreCase(server.getType()) ? HttpMethod.HEAD : HttpMethod.GET;
            ResponseEntity<String> resp = healthRestTemplate.exchange(probeUrl, method, HttpEntity.EMPTY, String.class);
            int latency = (int) Math.max(1, System.currentTimeMillis() - start);
            int statusCode = resp.getStatusCode().value();
            if (statusCode >= 200 && statusCode < 400) {
                return McpProbeResult.builder()
                        .status(latency < 2000 ? "online" : "degraded")
                        .latencyMs(latency)
                        .message("HTTP " + statusCode + " (" + latency + "ms)")
                        .probeUrl(probeUrl)
                        .build();
            }
            return McpProbeResult.builder()
                    .status("offline")
                    .latencyMs(latency)
                    .message("HTTP " + statusCode)
                    .probeUrl(probeUrl)
                    .build();
        } catch (Exception e) {
            int latency = (int) Math.max(1, System.currentTimeMillis() - start);
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (error.length() > 250) {
                error = error.substring(0, 250);
            }
            return McpProbeResult.builder()
                    .status("offline")
                    .latencyMs(latency)
                    .message(error)
                    .probeUrl(probeUrl)
                    .build();
        }
    }

    private McpEndpoint toEndpoint(McpServerEntity server, String probeUrl) {
        McpEndpoint endpoint = new McpEndpoint();
        endpoint.setType(StringUtils.hasText(server.getType()) ? server.getType() : "http");
        endpoint.setUrl(probeUrl);
        endpoint.setHeaders(server.getHeaders());

        Map<String, Object> configJson = server.getConfigJson();
        if (configJson != null) {
            endpoint.setCommand(parseCommand(configJson.get("command")));
            endpoint.setEnv(parseEnv(configJson.get("env")));
        }
        return endpoint;
    }

    private List<String> parseCommand(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> cmd = new ArrayList<>();
            for (Object item : list) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    cmd.add(String.valueOf(item));
                }
            }
            return cmd;
        }
        if (raw instanceof String text && StringUtils.hasText(text)) {
            return List.of(text);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseEnv(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Collections.emptyMap();
        }
        Map<String, String> env = new LinkedHashMap<>();
        ((Map<Object, Object>) map).forEach((k, v) -> {
            if (k != null && v != null && StringUtils.hasText(String.valueOf(k))) {
                env.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return env;
    }

    private void handleFailure(McpServerEntity server, String errorMessage, int latencyMs) {
        int currentFailures = server.getConsecutiveFailures() != null ? server.getConsecutiveFailures() : 0;
        int newFailures = currentFailures + 1;
        String status = newFailures >= 3 ? "offline" : "degraded";

        if (errorMessage != null && errorMessage.length() > 250) {
            errorMessage = errorMessage.substring(0, 250);
        }

        mcpServerService.updateHealth(server.getId(), status, latencyMs, errorMessage, newFailures);
        log.warn("MCP server {} health check failed (attempt {}): {} -> {}",
                server.getName(), newFailures, errorMessage, status);
    }

    private String resolveProbeUrl(McpServerEntity server) {
        if (StringUtils.hasText(server.getHealthCheckUrl())) {
            return server.getHealthCheckUrl();
        }
        if (StringUtils.hasText(server.getUrl())) {
            return server.getUrl();
        }
        return server.getEndpoint();
    }

    private boolean hasDedicatedHealthCheckUrl(McpServerEntity server) {
        return StringUtils.hasText(server.getHealthCheckUrl());
    }
}
