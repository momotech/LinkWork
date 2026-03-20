package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linkwork.common.ForbiddenOperationException;
import com.linkwork.common.ResourceNotFoundException;
import com.linkwork.mapper.McpServerMapper;
import com.linkwork.model.entity.McpServerEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP 服务 Service
 */
@Slf4j
@Service
public class McpServerService extends ServiceImpl<McpServerMapper, McpServerEntity> {

    @Value("${robot.mcp-gateway.agent-base-url:}")
    private String mcpGatewayAgentBaseUrl;

    @Autowired
    private McpCryptoService cryptoService;

    private static final Set<String> SUPPORTED_TYPES = Set.of("http", "sse");
    private static final Set<String> SUPPORTED_VISIBILITIES = Set.of("public", "private");
    private static final Set<String> SUPPORTED_STATUSES = Set.of("online", "degraded", "offline", "unknown");
    private static final Set<String> SENSITIVE_HEADER_KEYS = Set.of(
            "authorization", "proxy-authorization",
            "cookie", "set-cookie",
            "x-api-key", "apikey", "api-key",
            "token", "access-token", "refresh-token",
            "secret", "client-secret", "app-secret",
            "password", "passwd"
    );
    private static final Set<String> SUPPORTED_NETWORK_ZONES = Set.of("internal", "office", "external");
    @Autowired
    private AdminAccessService adminAccessService;

    /**
     * 创建 MCP 服务
     */
    @SuppressWarnings("unchecked")
    public McpServerEntity createMcpServer(Map<String, Object> request, String userId, String userName) {
        McpServerEntity entity = new McpServerEntity();
        entity.setMcpNo("MCP-" + System.currentTimeMillis());
        entity.setName(normalizeRequiredText(request.get("name"), "MCP 名称不能为空"));
        entity.setEndpoint(normalizeOptionalText(request.get("endpoint")));
        entity.setDescription(normalizeOptionalText(request.get("description")));
        entity.setVisibility(normalizeVisibility(request.getOrDefault("visibility", "private")));
        entity.setStatus("unknown");
        entity.setCreatorId(userId);
        entity.setCreatorName(userName);

        entity.setType(normalizeType(request.getOrDefault("type", "http")));
        entity.setUrl(normalizeOptionalText(request.get("url")));
        entity.setHealthCheckUrl(normalizeOptionalText(request.get("healthCheckUrl")));
        entity.setVersion(normalizeOptionalText(request.get("version")));
        entity.setNetworkZone(normalizeNetworkZone(request.getOrDefault("networkZone", "external")));
        entity.setConsecutiveFailures(0);

        if (request.containsKey("headers")) {
            entity.setHeaders((Map<String, String>) request.get("headers"));
        }
        if (request.containsKey("tags")) {
            entity.setTags((List<String>) request.get("tags"));
        }
        if (request.containsKey("configJson")) {
            entity.setConfigJson((Map<String, Object>) request.get("configJson"));
        }
        validateConnectivityFields(entity);
        encryptSensitiveFields(entity);

        this.save(entity);
        log.info("Created MCP server: {} (type={}) by user {}", entity.getMcpNo(), entity.getType(), userId);
        decryptSensitiveFields(entity);
        return entity;
    }

    /**
     * 更新 MCP 服务
     */
    @SuppressWarnings("unchecked")
    public McpServerEntity updateMcpServer(Long id, Map<String, Object> request, String userId, String userName) {
        McpServerEntity entity = requireOwnedMcpServer(id, userId);

        if (request.containsKey("name")) {
            entity.setName(normalizeRequiredText(request.get("name"), "MCP 名称不能为空"));
        }
        if (request.containsKey("endpoint")) {
            entity.setEndpoint(normalizeOptionalText(request.get("endpoint")));
        }
        if (request.containsKey("description")) {
            entity.setDescription(normalizeOptionalText(request.get("description")));
        }
        if (request.containsKey("visibility")) {
            entity.setVisibility(normalizeVisibility(request.get("visibility")));
        }
        if (request.containsKey("status")) {
            entity.setStatus(normalizeStatus(request.get("status")));
        }
        if (request.containsKey("configJson")) {
            entity.setConfigJson((Map<String, Object>) request.get("configJson"));
        }

        if (request.containsKey("type")) {
            entity.setType(normalizeType(request.get("type")));
        }
        if (request.containsKey("url")) {
            entity.setUrl(normalizeOptionalText(request.get("url")));
        }
        if (request.containsKey("headers")) {
            entity.setHeaders((Map<String, String>) request.get("headers"));
        }
        if (request.containsKey("healthCheckUrl")) {
            entity.setHealthCheckUrl(normalizeOptionalText(request.get("healthCheckUrl")));
        }
        if (request.containsKey("version")) {
            entity.setVersion(normalizeOptionalText(request.get("version")));
        }
        if (request.containsKey("tags")) {
            entity.setTags((List<String>) request.get("tags"));
        }
        if (request.containsKey("networkZone")) {
            entity.setNetworkZone(normalizeNetworkZone(request.get("networkZone")));
        }
        validateConnectivityFields(entity);
        encryptSensitiveFields(entity);

        entity.setUpdaterId(userId);
        entity.setUpdaterName(userName);

        this.updateById(entity);
        log.info("Updated MCP server: {} by user {}", entity.getMcpNo(), userId);
        decryptSensitiveFields(entity);
        return entity;
    }

    /**
     * 按类型查询 MCP Server 列表（内部 API / Gateway 使用，需解密）
     */
    public List<McpServerEntity> listByTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<McpServerEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(McpServerEntity::getType, types);
        List<McpServerEntity> result = this.list(wrapper);
        result.forEach(this::decryptSensitiveFields);
        return result;
    }

    /**
     * 更新 MCP Server 健康状态
     */
    public void updateHealth(Long id, String status, Integer latencyMs, String message, int consecutiveFailures) {
        LambdaUpdateWrapper<McpServerEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(McpServerEntity::getId, id)
                .set(McpServerEntity::getStatus, status)
                .set(McpServerEntity::getHealthLatencyMs, latencyMs)
                .set(McpServerEntity::getHealthMessage, message)
                .set(McpServerEntity::getConsecutiveFailures, consecutiveFailures)
                .set(McpServerEntity::getLastHealthAt, LocalDateTime.now());
        this.update(wrapper);
    }

    /**
     * 根据 MCP ID 列表生成 SDK 兼容的 mcp.json 格式
     * <p>
     * 当 mcpGatewayAgentBaseUrl 配置非空时，URL 指向 Gateway 代理地址，不暴露原始 URL 和 Headers；
     * 未配置时回退为直连模式（向下兼容）。
     *
     * @return { "mcpServers": { "name": { "type": "http", "url": "...", "headers": {...} } }, "globalHeaders": {...} }
     */
    public Map<String, Object> generateMcpConfig(List<Long> mcpIds) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return Map.of("mcpServers", Collections.emptyMap());
        }

        List<McpServerEntity> servers = this.listByIds(mcpIds);
        servers.forEach(this::decryptSensitiveFields);
        Map<String, Object> mcpServers = new LinkedHashMap<>();

        boolean useGateway = StringUtils.hasText(mcpGatewayAgentBaseUrl);

        for (McpServerEntity server : servers) {
            Map<String, Object> serverConfig = new LinkedHashMap<>();
            serverConfig.put("type", server.getType() != null ? server.getType() : "http");

            if (useGateway) {
                String gatewayUrl = mcpGatewayAgentBaseUrl.replaceAll("/+$", "")
                        + "/proxy/" + server.getName() + "/mcp";
                serverConfig.put("url", gatewayUrl);
            } else {
                String serverUrl = server.getUrl();
                if (!StringUtils.hasText(serverUrl)) {
                    serverUrl = server.getEndpoint();
                }
                if (StringUtils.hasText(serverUrl)) {
                    serverConfig.put("url", serverUrl);
                }
                if (server.getHeaders() != null && !server.getHeaders().isEmpty()) {
                    serverConfig.put("headers", server.getHeaders());
                }
            }

            mcpServers.put(server.getName(), serverConfig);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mcpServers", mcpServers);

        if (useGateway) {
            Map<String, String> globalHeaders = new LinkedHashMap<>();
            globalHeaders.put("X-Task-Id", "{taskid}");
            globalHeaders.put("X-User-Id", "{userid}");
            result.put("globalHeaders", globalHeaders);
        }

        return result;
    }

    /**
     * 返回所有 MCP Server 的健康状态列表
     */
    public Map<String, Object> getHealthStatus(String userId) {
        if (!StringUtils.hasText(userId)) {
            return Map.of("items", List.of(), "checkedAt", LocalDateTime.now().toString());
        }
        LambdaQueryWrapper<McpServerEntity> wrapper = new LambdaQueryWrapper<>();
        if (!adminAccessService.isAdmin(userId)) {
            wrapper.eq(McpServerEntity::getCreatorId, userId);
        }
        List<McpServerEntity> allServers = this.list(wrapper);
        List<Map<String, Object>> items = allServers.stream()
                .map(this::toHealthMap)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("checkedAt", LocalDateTime.now().toString());
        return result;
    }

    /**
     * 获取 MCP 服务列表（分页）
     */
    public Map<String, Object> listMcpServers(int page, int pageSize, String status, String keyword, String userId) {
        Page<McpServerEntity> pageObj = new Page<>(page, pageSize);

        LambdaQueryWrapper<McpServerEntity> wrapper = new LambdaQueryWrapper<>();
        applyVisibilityScope(wrapper, userId);
        String normalizedStatus = normalizeStatusForQuery(status);
        if (StringUtils.hasText(normalizedStatus)) {
            wrapper.eq(McpServerEntity::getStatus, normalizedStatus);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(McpServerEntity::getName, keyword)
                    .or().like(McpServerEntity::getDescription, keyword));
        }
        wrapper.orderByDesc(McpServerEntity::getCreatedAt);

        Page<McpServerEntity> result = this.page(pageObj, wrapper);
        result.getRecords().forEach(this::decryptSensitiveFields);

        List<Map<String, Object>> items = result.getRecords().stream()
                .map(entity -> toResponseMap(entity, userId))
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("items", items);
        response.put("pagination", Map.of(
                "page", result.getCurrent(),
                "pageSize", result.getSize(),
                "total", result.getTotal(),
                "totalPages", result.getPages()
        ));
        return response;
    }

    /**
     * 获取所有可用的 MCP 服务（用于下拉选择）
     */
    public List<Map<String, Object>> listAllAvailable(String userId) {
        LambdaQueryWrapper<McpServerEntity> wrapper = new LambdaQueryWrapper<>();
        applyVisibilityScope(wrapper, userId);
        wrapper.orderByDesc(McpServerEntity::getCreatedAt);

        return this.list(wrapper).stream()
                .map(entity -> toSimpleMap(entity, userId))
                .collect(Collectors.toList());
    }

    /**
     * 获取当前用户可访问的 MCP 服务详情
     */
    public Map<String, Object> getMcpServerForRead(Long id, String userId) {
        McpServerEntity entity = requireReadableMcpServer(id, userId);
        return toResponseMap(entity, userId);
    }

    /**
     * 获取当前用户可操作（写/管理）的 MCP 服务详情
     */
    public McpServerEntity getMcpServerForManage(Long id, String userId) {
        McpServerEntity entity = requireOwnedMcpServer(id, userId);
        decryptSensitiveFields(entity);
        return entity;
    }

    /**
     * 删除 MCP 服务（仅创建者）
     */
    public void deleteMcpServer(Long id, String userId) {
        McpServerEntity entity = requireOwnedMcpServer(id, userId);
        this.removeById(entity.getId());
    }

    private McpServerEntity requireOwnedMcpServer(Long id, String userId) {
        McpServerEntity entity = this.getById(id);
        if (entity == null) {
            throw new ResourceNotFoundException("MCP server not found: " + id);
        }
        if (!canManage(entity, userId)) {
            throw new ForbiddenOperationException("仅 MCP 创建者或管理员可访问或修改");
        }
        return entity;
    }

    private McpServerEntity requireReadableMcpServer(Long id, String userId) {
        McpServerEntity entity = this.getById(id);
        if (entity == null) {
            throw new ResourceNotFoundException("MCP server not found: " + id);
        }
        if (adminAccessService.isAdmin(userId)) {
            return entity;
        }
        boolean isOwner = StringUtils.hasText(userId) && userId.equals(entity.getCreatorId());
        boolean isPublic = "public".equals(coerceVisibility(entity.getVisibility()));
        if (!isOwner && !isPublic) {
            throw new ForbiddenOperationException("无权限访问该 MCP 服务");
        }
        return entity;
    }

    private String normalizeOptionalText(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private String normalizeRequiredText(Object raw, String message) {
        String value = normalizeOptionalText(raw);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private String normalizeType(Object rawType) {
        String value = normalizeOptionalText(rawType);
        if (!StringUtils.hasText(value)) {
            return "http";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("非法 MCP 类型: " + value + "，仅支持 http/sse");
        }
        return normalized;
    }

    private String normalizeVisibility(Object rawVisibility) {
        String value = normalizeOptionalText(rawVisibility);
        if (!StringUtils.hasText(value)) {
            return "private";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_VISIBILITIES.contains(normalized)) {
            throw new IllegalArgumentException("非法 MCP 可见性: " + value + "，仅支持 public/private");
        }
        return normalized;
    }

    private String normalizeStatus(Object rawStatus) {
        String value = normalizeOptionalText(rawStatus);
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("非法 MCP 状态: " + value + "，仅支持 online/degraded/offline/unknown");
        }
        return normalized;
    }

    private String normalizeStatusForQuery(String rawStatus) {
        String value = normalizeOptionalText(rawStatus);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return normalizeStatus(value);
    }

    private String coerceVisibility(String rawVisibility) {
        if (!StringUtils.hasText(rawVisibility)) {
            return "private";
        }
        String normalized = rawVisibility.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_VISIBILITIES.contains(normalized) ? normalized : "private";
    }

    private String coerceStatus(String rawStatus) {
        if (!StringUtils.hasText(rawStatus)) {
            return "unknown";
        }
        String normalized = rawStatus.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_STATUSES.contains(normalized) ? normalized : "unknown";
    }

    private String normalizeNetworkZone(Object rawZone) {
        String value = normalizeOptionalText(rawZone);
        if (!StringUtils.hasText(value)) {
            return "external";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_NETWORK_ZONES.contains(normalized)) {
            throw new IllegalArgumentException("非法网段标记: " + value + "，仅支持 internal/office/external");
        }
        return normalized;
    }

    private void validateConnectivityFields(McpServerEntity entity) {
        if (!StringUtils.hasText(entity.getUrl()) && !StringUtils.hasText(entity.getEndpoint())) {
            throw new IllegalArgumentException("MCP url/endpoint 不能为空");
        }
    }

    /**
     * 保存前加密 URL 和 Headers（仅当加密服务启用时）
     */
    private void encryptSensitiveFields(McpServerEntity entity) {
        if (!cryptoService.isEnabled()) return;
        if (StringUtils.hasText(entity.getUrl())) {
            entity.setUrl(cryptoService.encrypt(entity.getUrl()));
        }
    }

    /**
     * 读取后解密 URL 和 Headers（兼容明文和密文混存）
     */
    private void decryptSensitiveFields(McpServerEntity entity) {
        if (!cryptoService.isEnabled() || entity == null) return;
        if (StringUtils.hasText(entity.getUrl())) {
            entity.setUrl(cryptoService.decrypt(entity.getUrl()));
        }
    }

    private void applyVisibilityScope(LambdaQueryWrapper<McpServerEntity> wrapper, String userId) {
        if (StringUtils.hasText(userId)) {
            if (adminAccessService.isAdmin(userId)) {
                return;
            }
            wrapper.and(w -> w.apply("creator_id = {0} OR visibility = 'public'", userId));
            return;
        }
        wrapper.apply("visibility = 'public'");
    }

    private Map<String, Object> toResponseMap(McpServerEntity entity, String userId) {
        boolean canManage = canManage(entity, userId);
        boolean masked = shouldMaskSensitiveFields(entity, userId);
        String urlForDisplay = firstNonBlank(entity.getUrl(), entity.getEndpoint());
        String healthUrlForDisplay = firstNonBlank(entity.getHealthCheckUrl(), entity.getUrl(), entity.getEndpoint());

        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId().toString());
        map.put("mcpNo", entity.getMcpNo());
        map.put("name", entity.getName());
        map.put("endpoint", masked ? null : entity.getEndpoint());
        map.put("description", entity.getDescription());
        map.put("visibility", coerceVisibility(entity.getVisibility()));
        map.put("status", coerceStatus(entity.getStatus()));
        map.put("type", entity.getType());
        map.put("url", masked ? null : entity.getUrl());
        map.put("headers", masked ? null : entity.getHeaders());
        map.put("networkZone", entity.getNetworkZone() != null ? entity.getNetworkZone() : "external");
        map.put("healthCheckUrl", masked ? null : entity.getHealthCheckUrl());
        map.put("displayUrl", maskUrlForDisplay(urlForDisplay));
        map.put("displayHeaders", maskHeadersForDisplay(entity.getHeaders()));
        map.put("displayHealthCheckUrl", maskUrlForDisplay(healthUrlForDisplay));
        map.put("canManage", canManage);
        map.put("masked", masked);
        map.put("healthLatencyMs", entity.getHealthLatencyMs());
        map.put("healthMessage", entity.getHealthMessage());
        map.put("consecutiveFailures", entity.getConsecutiveFailures());
        map.put("version", entity.getVersion());
        map.put("tags", entity.getTags());
        map.put("lastHealthAt", formatDateTime(entity.getLastHealthAt()));
        map.put("configJson", entity.getConfigJson());
        map.put("creatorId", entity.getCreatorId());
        map.put("creatorName", entity.getCreatorName());
        map.put("createdAt", formatDateTime(entity.getCreatedAt()));
        map.put("updatedAt", formatDateTime(entity.getUpdatedAt()));

        return map;
    }

    private boolean isOwner(McpServerEntity entity, String userId) {
        return StringUtils.hasText(userId) && userId.equals(entity.getCreatorId());
    }

    private boolean canManage(McpServerEntity entity, String userId) {
        return adminAccessService.isAdmin(userId) || isOwner(entity, userId);
    }

    private boolean shouldMaskSensitiveFields(McpServerEntity entity, String userId) {
        return !canManage(entity, userId) && "public".equals(coerceVisibility(entity.getVisibility()));
    }

    private String firstNonBlank(String... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String maskUrlForDisplay(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            return null;
        }
        String value = rawUrl.trim();
        try {
            URI uri = new URI(value);
            if (StringUtils.hasText(uri.getScheme()) && StringUtils.hasText(uri.getHost())) {
                StringBuilder builder = new StringBuilder();
                builder.append(uri.getScheme()).append("://").append(uri.getHost());
                if (uri.getPort() > 0) {
                    builder.append(":").append(uri.getPort());
                }
                builder.append("/***");
                return builder.toString();
            }
        } catch (URISyntaxException ignored) {
            // ignore and fallback to generic mask
        }
        return maskGenericValue(value);
    }

    private Map<String, String> maskHeadersForDisplay(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String headerName = entry.getKey() == null ? "" : entry.getKey().trim();
            String lowerKey = headerName.toLowerCase(Locale.ROOT);
            String headerValue = entry.getValue();
            if (isSensitiveHeaderKey(lowerKey)) {
                masked.put(headerName, "***");
            } else {
                masked.put(headerName, maskGenericValue(headerValue));
            }
        }
        return masked;
    }

    private boolean isSensitiveHeaderKey(String lowerKey) {
        if (!StringUtils.hasText(lowerKey)) {
            return false;
        }
        if (SENSITIVE_HEADER_KEYS.contains(lowerKey)) {
            return true;
        }
        return lowerKey.contains("token")
                || lowerKey.contains("secret")
                || lowerKey.contains("password")
                || lowerKey.contains("cookie")
                || lowerKey.contains("auth");
    }

    private String maskGenericValue(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "***";
        }
        String value = raw.trim();
        if (value.length() <= 8) {
            return "***";
        }
        return value.substring(0, 3) + "***" + value.substring(value.length() - 2);
    }

    private Map<String, Object> toSimpleMap(McpServerEntity entity, String userId) {
        boolean canManage = canManage(entity, userId);
        boolean masked = shouldMaskSensitiveFields(entity, userId);
        String urlForDisplay = firstNonBlank(entity.getUrl(), entity.getEndpoint());

        Map<String, Object> map = new HashMap<>();
        map.put("id", entity.getId().toString());
        map.put("name", entity.getName());
        map.put("description", entity.getDescription());
        map.put("endpoint", masked ? null : entity.getEndpoint());
        map.put("url", masked ? null : entity.getUrl());
        map.put("displayUrl", maskUrlForDisplay(urlForDisplay));
        map.put("visibility", coerceVisibility(entity.getVisibility()));
        map.put("status", coerceStatus(entity.getStatus()));
        map.put("type", entity.getType());
        map.put("networkZone", entity.getNetworkZone() != null ? entity.getNetworkZone() : "external");
        map.put("canManage", canManage);
        map.put("masked", masked);
        return map;
    }

    private Map<String, Object> toHealthMap(McpServerEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId().toString());
        map.put("name", entity.getName());
        map.put("type", entity.getType());
        map.put("status", coerceStatus(entity.getStatus()));
        map.put("latencyMs", entity.getHealthLatencyMs());
        map.put("lastHealthAt", formatDateTime(entity.getLastHealthAt()));
        map.put("consecutiveFailures", entity.getConsecutiveFailures());
        map.put("healthMessage", entity.getHealthMessage());
        return map;
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
