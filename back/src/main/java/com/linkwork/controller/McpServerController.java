package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.context.UserContext;
import com.linkwork.model.dto.McpDiscoverResult;
import com.linkwork.model.dto.McpProbeResult;
import com.linkwork.model.entity.McpServerEntity;
import com.linkwork.model.entity.RoleEntity;
import com.linkwork.service.McpDiscoveryService;
import com.linkwork.service.McpHealthChecker;
import com.linkwork.service.McpServerService;
import com.linkwork.service.RoleService;
import com.linkwork.service.AdminAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP 服务控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class McpServerController {

    private final McpServerService mcpServerService;
    private final McpHealthChecker mcpHealthChecker;
    private final McpDiscoveryService mcpDiscoveryService;
    private final RoleService roleService;
    private final AdminAccessService adminAccessService;

    /**
     * 获取 MCP 服务列表
     */
    @GetMapping("/mcp-servers")
    public ApiResponse<Map<String, Object>> listMcpServers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        String userId = UserContext.getCurrentUserId();
        Map<String, Object> data = mcpServerService.listMcpServers(page, pageSize, status, keyword, userId);
        return ApiResponse.success(data);
    }

    /**
     * 获取所有可用的 MCP 服务（用于下拉选择）
     */
    @GetMapping("/mcp-servers/available")
    public ApiResponse<List<Map<String, Object>>> listAvailable() {
        String userId = UserContext.getCurrentUserId();
        List<Map<String, Object>> data = mcpServerService.listAllAvailable(userId);
        return ApiResponse.success(data);
    }

    /**
     * 获取所有 MCP Server 的健康状态
     */
    @GetMapping("/mcp-servers/health")
    public ApiResponse<Map<String, Object>> getHealthStatus() {
        String userId = UserContext.getCurrentUserId();
        Map<String, Object> data = mcpServerService.getHealthStatus(userId);
        return ApiResponse.success(data);
    }

    /**
     * 获取单个 MCP 服务详情
     */
    @GetMapping("/mcp-servers/{id}")
    public ApiResponse<Map<String, Object>> getMcpServer(@PathVariable Long id) {
        String userId = UserContext.getCurrentUserId();
        Map<String, Object> entity = mcpServerService.getMcpServerForRead(id, userId);
        return ApiResponse.success(entity);
    }

    /**
     * 测试单个 MCP 服务的连通性
     */
    @PostMapping("/mcp-servers/{id}/test")
    public ApiResponse<McpProbeResult> testMcpServer(@PathVariable Long id) {
        String userId = UserContext.getCurrentUserId();
        McpServerEntity entity = mcpServerService.getMcpServerForManage(id, userId);
        McpProbeResult result = mcpHealthChecker.probeSingle(entity);
        // 同时更新 DB 健康状态
        mcpServerService.updateHealth(entity.getId(), result.getStatus(), result.getLatencyMs(), result.getMessage(),
                "online".equals(result.getStatus()) ? 0
                        : (entity.getConsecutiveFailures() != null ? entity.getConsecutiveFailures() + 1 : 1));
        return ApiResponse.success(result);
    }

    /**
     * 发现 MCP Server 的工具列表
     * <p>
     * 对目标 MCP server 发送 JSON-RPC 请求（initialize → initialized → tools/list），
     * 获取并返回工具清单。成功时同步更新 configJson。
     */
    @PostMapping("/mcp-servers/{id}/discover")
    public ApiResponse<McpDiscoverResult> discoverTools(@PathVariable Long id) {
        String userId = UserContext.getCurrentUserId();
        McpServerEntity entity = mcpServerService.getMcpServerForManage(id, userId);

        McpDiscoverResult result = mcpDiscoveryService.discover(entity, userId);

        // 如果成功，把 tools 存入 config_json
        if (result.isSuccess() && result.getTools() != null) {
            Map<String, Object> configJson = entity.getConfigJson();
            if (configJson == null) {
                configJson = new HashMap<>();
            }
            configJson.put("tools", result.getTools());
            configJson.put("serverName", result.getServerName());
            configJson.put("serverVersion", result.getServerVersion());
            configJson.put("lastDiscoveredAt", LocalDateTime.now().toString());
            entity.setConfigJson(configJson);
            mcpServerService.updateById(entity);
        }

        return ApiResponse.success(result);
    }

    /**
     * 创建 MCP 服务（支持 type/url/headers/healthCheckUrl/version/tags）
     */
    @PostMapping("/mcp-servers")
    public ApiResponse<Map<String, Object>> createMcpServer(
            @RequestBody Map<String, Object> request) {
        String userId = UserContext.getCurrentUserId();
        String userName = UserContext.getCurrentUserName();
        McpServerEntity entity = mcpServerService.createMcpServer(request, userId, userName);
        return ApiResponse.success(Map.of("id", entity.getId(), "mcpNo", entity.getMcpNo()));
    }

    /**
     * 更新 MCP 服务（支持 type/url/headers/healthCheckUrl/version/tags）
     */
    @PutMapping("/mcp-servers/{id}")
    public ApiResponse<Map<String, Object>> updateMcpServer(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        String userId = UserContext.getCurrentUserId();
        String userName = UserContext.getCurrentUserName();
        McpServerEntity entity = mcpServerService.updateMcpServer(id, request, userId, userName);
        return ApiResponse.success(Map.of("id", entity.getId(), "mcpNo", entity.getMcpNo()));
    }

    /**
     * 删除 MCP 服务
     */
    @DeleteMapping("/mcp-servers/{id}")
    public ApiResponse<Void> deleteMcpServer(@PathVariable Long id) {
        String userId = UserContext.getCurrentUserId();
        mcpServerService.deleteMcpServer(id, userId);
        return ApiResponse.success(null);
    }

    /**
     * 根据岗位生成 mcp.json 配置
     * <p>
     * 从岗位 configJson.mcp 中获取 MCP ID 列表，查询对应 MCP Server 配置，
     * 生成 SDK 兼容的 mcp.json 格式。
     */
    @GetMapping("/roles/{roleId}/mcp-config")
    public ApiResponse<Map<String, Object>> getMcpConfigByRole(@PathVariable Long roleId) {
        String userId = UserContext.getCurrentUserId();
        RoleEntity role = roleService.getRoleForWrite(roleId, userId);

        // 兼容数字 ID 和名称字符串两种格式
        List<Long> mcpIds = new ArrayList<>();
        if (role.getConfigJson() != null && role.getConfigJson().getMcp() != null) {
            List<String> mcpNames = new ArrayList<>();
            for (String ref : role.getConfigJson().getMcp()) {
                if (ref == null || ref.isBlank()) continue;
                try {
                    mcpIds.add(Long.parseLong(ref));
                } catch (NumberFormatException e) {
                    mcpNames.add(ref);
                }
            }
            // 按名称查询
            if (!mcpNames.isEmpty()) {
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<McpServerEntity> byNameQuery =
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<McpServerEntity>()
                                .in(McpServerEntity::getName, mcpNames);
                if (!adminAccessService.isAdmin(userId)) {
                    byNameQuery.and(w -> w.eq(McpServerEntity::getCreatorId, userId)
                            .or().eq(McpServerEntity::getVisibility, "public"));
                }
                List<McpServerEntity> byNames = mcpServerService.list(byNameQuery);
                for (McpServerEntity entity : byNames) {
                    mcpIds.add(entity.getId());
                }
            }
        }

        Map<String, Object> config = mcpServerService.generateMcpConfig(mcpIds);
        return ApiResponse.success(config);
    }
}
