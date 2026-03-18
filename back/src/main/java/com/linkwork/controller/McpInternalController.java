package com.linkwork.controller;

import com.linkwork.common.api.ApiResponse;
import com.linkwork.model.mcp.McpServerRecord;
import com.linkwork.model.mcp.McpUserConfigRecord;
import com.linkwork.service.mcp.McpServerService;
import com.linkwork.service.mcp.McpUserConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/internal")
public class McpInternalController {

    private final McpServerService mcpServerService;
    private final McpUserConfigService mcpUserConfigService;

    public McpInternalController(McpServerService mcpServerService,
                                 McpUserConfigService mcpUserConfigService) {
        this.mcpServerService = mcpServerService;
        this.mcpUserConfigService = mcpUserConfigService;
    }

    @GetMapping("/mcp-servers/registry")
    public ApiResponse<Map<String, Object>> registry() {
        List<McpServerRecord> allServers = mcpServerService.listByTypes(List.of("http", "sse"));
        List<Map<String, Object>> servers = new ArrayList<>();
        for (McpServerRecord server : allServers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", server.getName());
            item.put("type", server.getType());
            item.put("networkZone", server.getNetworkZone() != null ? server.getNetworkZone() : "external");
            item.put("url", server.getUrl());
            item.put("headers", server.getHeaders());
            item.put("healthCheckUrl", server.getHealthCheckUrl());
            item.put("status", server.getStatus());
            servers.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("servers", servers);
        result.put("updatedAt", LocalDateTime.now().toString());
        return ApiResponse.success(result);
    }

    @GetMapping("/mcp-user-configs")
    public ApiResponse<McpUserConfigRecord> getUserConfig(@RequestParam String mcpName,
                                                          @RequestParam String userId) {
        return ApiResponse.success(mcpUserConfigService.getByUserAndMcpName(userId, mcpName));
    }

    @GetMapping("/tasks/validate")
    public ApiResponse<Map<String, Object>> validateTask(@RequestParam String taskId,
                                                         @RequestParam(required = false) String userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("valid", false);
        result.put("userId", userId == null ? "" : userId);
        result.put("message", "task module not integrated yet");
        return ApiResponse.success(result);
    }
}
