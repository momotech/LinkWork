package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.model.entity.McpServerEntity;
import com.linkwork.model.entity.McpUserConfigEntity;
import com.linkwork.model.entity.Task;
import com.linkwork.model.enums.TaskStatus;
import com.linkwork.service.McpServerService;
import com.linkwork.service.McpUserConfigService;
import com.linkwork.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * MCP Gateway 内部 API — 仅供 Gateway 调用，不经过用户认证
 * <p>
 * /api/internal/** 路径已在 JwtAuthFilter 中放行
 */
@Slf4j
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
public class McpInternalController {

    private final McpServerService mcpServerService;
    private final McpUserConfigService mcpUserConfigService;
    private final TaskService taskService;

    /**
     * Gateway 定时拉取完整 MCP Server 注册表
     */
    @GetMapping("/mcp-servers/registry")
    public ApiResponse<Map<String, Object>> registry() {
        List<McpServerEntity> allServers = mcpServerService.listByTypes(List.of("http", "sse"));
        List<Map<String, Object>> servers = new ArrayList<>();

        for (McpServerEntity s : allServers) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", s.getName());
            entry.put("type", s.getType());
            entry.put("networkZone", s.getNetworkZone() != null ? s.getNetworkZone() : "external");
            entry.put("url", s.getUrl());
            entry.put("headers", s.getHeaders());
            entry.put("healthCheckUrl", s.getHealthCheckUrl());
            entry.put("status", s.getStatus());
            servers.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("servers", servers);
        result.put("updatedAt", LocalDateTime.now().toString());
        return ApiResponse.success(result);
    }

    /**
     * Gateway 验证 taskId（即 taskNo）是否有效且未结束
     */
    @GetMapping("/tasks/{taskId}/validate")
    public ApiResponse<Map<String, Object>> validateTask(@PathVariable String taskId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);

        try {
            Task task = taskService.getTaskByNo(taskId);
            boolean active = task.getStatus() == TaskStatus.PENDING
                    || task.getStatus() == TaskStatus.RUNNING
                    || task.getStatus() == TaskStatus.PENDING_AUTH;
            data.put("valid", active);
            data.put("userId", task.getCreatorId() != null ? task.getCreatorId() : "");
        } catch (IllegalArgumentException e) {
            data.put("valid", false);
            data.put("userId", "");
        }
        return ApiResponse.success(data);
    }

    /**
     * Gateway 查询用户个人 MCP 凭证
     */
    @GetMapping("/mcp-user-configs")
    public ApiResponse<McpUserConfigEntity> getUserConfig(
            @RequestParam String mcpName,
            @RequestParam String userId) {
        McpUserConfigEntity config = mcpUserConfigService.getByUserAndMcpName(userId, mcpName);
        return ApiResponse.success(config);
    }
}
