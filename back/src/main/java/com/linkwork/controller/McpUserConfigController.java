package com.linkwork.controller;

import com.linkwork.common.api.ApiResponse;
import com.linkwork.model.mcp.McpUserConfigRecord;
import com.linkwork.service.mcp.McpUserConfigService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/mcp-user-configs")
@CrossOrigin(origins = "*")
public class McpUserConfigController {

    private final McpUserConfigService mcpUserConfigService;

    public McpUserConfigController(McpUserConfigService mcpUserConfigService) {
        this.mcpUserConfigService = mcpUserConfigService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> data = mcpUserConfigService.listCurrentUser().stream()
            .map(record -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", record.getId());
                map.put("mcpServerId", record.getMcpServerId());
                map.put("hasHeaders", record.getHeaders() != null && !record.getHeaders().isEmpty());
                map.put("hasUrlParams", record.getUrlParams() != null && !record.getUrlParams().isEmpty());
                map.put("createdAt", record.getCreatedAt());
                map.put("updatedAt", record.getUpdatedAt());
                return map;
            })
            .collect(Collectors.toList());
        return ApiResponse.success(data);
    }

    @GetMapping("/{mcpServerId}/detail")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long mcpServerId) {
        McpUserConfigRecord record = mcpUserConfigService.getCurrentUserConfig(mcpServerId);
        if (record == null) {
            return ApiResponse.success(Map.of("headers", Map.of(), "urlParams", Map.of()));
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("headers", record.getHeaders() == null ? Map.of() : record.getHeaders());
        map.put("urlParams", record.getUrlParams() == null ? Map.of() : record.getUrlParams());
        return ApiResponse.success(map);
    }

    @PutMapping("/{mcpServerId}")
    public ApiResponse<Map<String, Object>> saveOrUpdate(@PathVariable Long mcpServerId,
                                                         @RequestBody Map<String, Object> request) {
        McpUserConfigRecord updated = mcpUserConfigService.saveOrUpdateCurrentUser(mcpServerId, request);
        return ApiResponse.success(Map.of("id", updated.getId()));
    }

    @DeleteMapping("/{mcpServerId}")
    public ApiResponse<Void> delete(@PathVariable Long mcpServerId) {
        mcpUserConfigService.deleteCurrentUserConfig(mcpServerId);
        return ApiResponse.success(null);
    }
}
