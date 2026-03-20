package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.context.UserContext;
import com.linkwork.model.entity.McpUserConfigEntity;
import com.linkwork.service.McpUserConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/mcp-user-configs")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class McpUserConfigController {

    private final McpUserConfigService mcpUserConfigService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        String userId = UserContext.getCurrentUserId();
        List<McpUserConfigEntity> configs = mcpUserConfigService.listByUser(userId);
        List<Map<String, Object>> result = configs.stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("mcpServerId", c.getMcpServerId());
                    m.put("hasHeaders", c.getHeaders() != null && !c.getHeaders().isEmpty());
                    m.put("hasUrlParams", c.getUrlParams() != null && !c.getUrlParams().isEmpty());
                    m.put("createdAt", c.getCreatedAt());
                    m.put("updatedAt", c.getUpdatedAt());
                    return m;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    @GetMapping("/{mcpServerId}/detail")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long mcpServerId) {
        String userId = UserContext.getCurrentUserId();
        McpUserConfigEntity config = mcpUserConfigService.getByUserAndServer(userId, mcpServerId);
        if (config == null) {
            return ApiResponse.success(Map.of("headers", Map.of(), "urlParams", Map.of()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("headers", config.getHeaders() != null ? config.getHeaders() : Map.of());
        result.put("urlParams", config.getUrlParams() != null ? config.getUrlParams() : Map.of());
        return ApiResponse.success(result);
    }

    @PutMapping("/{mcpServerId}")
    public ApiResponse<Map<String, Object>> saveOrUpdate(
            @PathVariable Long mcpServerId,
            @RequestBody Map<String, Object> request) {
        String userId = UserContext.getCurrentUserId();
        McpUserConfigEntity entity = mcpUserConfigService.saveOrUpdate(userId, mcpServerId, request);
        return ApiResponse.success(Map.of("id", entity.getId()));
    }

    @DeleteMapping("/{mcpServerId}")
    public ApiResponse<Void> delete(@PathVariable Long mcpServerId) {
        String userId = UserContext.getCurrentUserId();
        mcpUserConfigService.deleteConfig(userId, mcpServerId);
        return ApiResponse.success(null);
    }
}
