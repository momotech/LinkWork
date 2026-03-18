package com.linkwork.controller;

import com.linkwork.common.api.ApiResponse;
import com.linkwork.model.mcp.dto.McpDiscoverResult;
import com.linkwork.model.mcp.dto.McpProbeResult;
import com.linkwork.model.mcp.McpServerRecord;
import com.linkwork.service.mcp.McpDiscoveryService;
import com.linkwork.service.mcp.McpHealthChecker;
import com.linkwork.service.mcp.McpServerService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class McpServerController {

    private final McpServerService mcpServerService;
    private final McpHealthChecker mcpHealthChecker;
    private final McpDiscoveryService mcpDiscoveryService;

    public McpServerController(McpServerService mcpServerService,
                               McpHealthChecker mcpHealthChecker,
                               McpDiscoveryService mcpDiscoveryService) {
        this.mcpServerService = mcpServerService;
        this.mcpHealthChecker = mcpHealthChecker;
        this.mcpDiscoveryService = mcpDiscoveryService;
    }

    @GetMapping("/mcp-servers")
    public ApiResponse<Map<String, Object>> listMcpServers(@RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int pageSize,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) String keyword) {
        return ApiResponse.success(mcpServerService.listMcpServers(page, pageSize, status, keyword));
    }

    @GetMapping("/mcp-servers/available")
    public ApiResponse<List<Map<String, Object>>> listAvailable() {
        return ApiResponse.success(mcpServerService.listAllAvailable());
    }

    @GetMapping("/mcp-servers/health")
    public ApiResponse<Map<String, Object>> getHealthStatus() {
        return ApiResponse.success(mcpServerService.getHealthStatus());
    }

    @GetMapping("/mcp-servers/{id}")
    public ApiResponse<Map<String, Object>> getMcpServer(@PathVariable Long id) {
        return ApiResponse.success(mcpServerService.getMcpServerForRead(id));
    }

    @PostMapping("/mcp-servers/{id}/test")
    public ApiResponse<McpProbeResult> testMcpServer(@PathVariable Long id) {
        McpServerRecord server = mcpServerService.getMcpServerForManage(id);
        McpProbeResult result = mcpHealthChecker.probeSingle(server);
        int nextFailures = "online".equals(result.getStatus())
            ? 0
            : (server.getConsecutiveFailures() != null ? server.getConsecutiveFailures() + 1 : 1);
        mcpServerService.updateHealth(server.getId(), result.getStatus(), result.getLatencyMs(), result.getMessage(), nextFailures);
        return ApiResponse.success(result);
    }

    @PostMapping("/mcp-servers/{id}/discover")
    public ApiResponse<McpDiscoverResult> discoverTools(@PathVariable Long id) {
        McpServerRecord server = mcpServerService.getMcpServerForManage(id);
        McpDiscoverResult result = mcpDiscoveryService.discover(server);
        if (result.isSuccess() && result.getTools() != null) {
            Map<String, Object> configJson = server.getConfigJson() == null ? new HashMap<>() : new HashMap<>(server.getConfigJson());
            configJson.put("tools", result.getTools());
            configJson.put("serverName", result.getServerName());
            configJson.put("serverVersion", result.getServerVersion());
            configJson.put("lastDiscoveredAt", LocalDateTime.now().toString());
            Map<String, Object> request = new HashMap<>();
            request.put("configJson", configJson);
            mcpServerService.updateMcpServer(id, request);
        }
        return ApiResponse.success(result);
    }

    @PostMapping("/mcp-servers")
    public ApiResponse<Map<String, Object>> createMcpServer(@RequestBody Map<String, Object> request) {
        McpServerRecord created = mcpServerService.createMcpServer(request);
        return ApiResponse.success(Map.of("id", created.getId(), "mcpNo", created.getMcpNo()));
    }

    @PutMapping("/mcp-servers/{id}")
    public ApiResponse<Map<String, Object>> updateMcpServer(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> request) {
        McpServerRecord updated = mcpServerService.updateMcpServer(id, request);
        return ApiResponse.success(Map.of("id", updated.getId(), "mcpNo", updated.getMcpNo()));
    }

    @DeleteMapping("/mcp-servers/{id}")
    public ApiResponse<Void> deleteMcpServer(@PathVariable Long id) {
        mcpServerService.deleteMcpServer(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/roles/{roleId}/mcp-config")
    public ApiResponse<Map<String, Object>> getMcpConfigByRole(@PathVariable Long roleId,
                                                               @RequestParam(name = "mcpRefs", required = false) List<String> mcpRefs) {
        List<String> refs = mcpRefs == null ? new ArrayList<>() : new ArrayList<>(mcpRefs);
        List<Long> ids = mcpServerService.resolveIdsByRefs(refs);
        return ApiResponse.success(mcpServerService.generateMcpConfig(ids));
    }

    @PostMapping("/mcp-servers/config")
    public ApiResponse<Map<String, Object>> generateConfig(@RequestBody(required = false) Map<String, Object> request) {
        List<Long> ids = new ArrayList<>();
        if (request != null && request.get("mcpIds") instanceof List<?> rawIds) {
            for (Object item : rawIds) {
                if (item == null) {
                    continue;
                }
                try {
                    ids.add(Long.parseLong(String.valueOf(item)));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return ApiResponse.success(mcpServerService.generateMcpConfig(ids));
    }
}
