package com.linkwork.service.mcp;

import com.momo.agent.mcp.core.McpClient;
import com.momo.agent.mcp.core.model.McpEndpoint;
import com.momo.agent.mcp.core.model.McpProbeResponse;
import com.linkwork.model.mcp.dto.McpProbeResult;
import com.linkwork.model.mcp.McpServerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class McpHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(McpHealthChecker.class);

    private final McpServerService mcpServerService;
    private final McpClient mcpClient;

    public McpHealthChecker(McpServerService mcpServerService, McpClient mcpClient) {
        this.mcpServerService = mcpServerService;
        this.mcpClient = mcpClient;
    }

    @Scheduled(fixedRate = 30_000)
    public void healthCheckAll() {
        try {
            List<McpServerRecord> servers = mcpServerService.listByTypes(List.of("http", "sse"));
            for (McpServerRecord server : servers) {
                checkSingle(server);
            }
        } catch (Exception ex) {
            log.warn("Skip MCP health check this round: {}", ex.getMessage());
        }
    }

    public McpProbeResult probeSingle(McpServerRecord server) {
        String probeUrl = resolveProbeUrl(server);
        if (!StringUtils.hasText(probeUrl)) {
            return McpProbeResult.of("offline", 0, "No probe URL configured", null);
        }

        McpEndpoint endpoint = new McpEndpoint();
        endpoint.setType(server.getType());
        endpoint.setUrl(probeUrl);
        endpoint.setHeaders(server.getHeaders());
        McpProbeResponse response = mcpClient.probe(endpoint);

        if (!response.isSuccess()) {
            return McpProbeResult.of("offline", response.getLatencyMs(), response.getMessage(), probeUrl);
        }

        String status = response.getLatencyMs() < 2000 ? "online" : "degraded";
        return McpProbeResult.of(status, response.getLatencyMs(), "HTTP probe success (" + response.getLatencyMs() + "ms)", probeUrl);
    }

    private void checkSingle(McpServerRecord server) {
        McpProbeResult result = probeSingle(server);
        if ("online".equals(result.getStatus()) || "degraded".equals(result.getStatus())) {
            int consecutiveFailures = "online".equals(result.getStatus())
                ? 0
                : (server.getConsecutiveFailures() != null ? server.getConsecutiveFailures() + 1 : 1);
            mcpServerService.updateHealth(server.getId(), result.getStatus(), result.getLatencyMs(), result.getMessage(), consecutiveFailures);
            return;
        }
        handleFailure(server, result.getMessage(), result.getLatencyMs());
    }

    private void handleFailure(McpServerRecord server, String errorMessage, int latencyMs) {
        int currentFailures = server.getConsecutiveFailures() != null ? server.getConsecutiveFailures() : 0;
        int newFailures = currentFailures + 1;
        String status = newFailures >= 3 ? "offline" : "degraded";

        String sanitized = errorMessage;
        if (sanitized != null && sanitized.length() > 250) {
            sanitized = sanitized.substring(0, 250);
        }
        mcpServerService.updateHealth(server.getId(), status, latencyMs, sanitized, newFailures);
    }

    private String resolveProbeUrl(McpServerRecord server) {
        if (StringUtils.hasText(server.getHealthCheckUrl())) {
            return server.getHealthCheckUrl();
        }
        if (StringUtils.hasText(server.getUrl())) {
            return server.getUrl();
        }
        return server.getEndpoint();
    }
}
