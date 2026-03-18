package com.linkwork.service.mcp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.momo.agent.mcp.McpProperties;
import com.linkwork.mapper.mcp.McpServerMapper;
import com.linkwork.model.mcp.McpServerRecord;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class McpServerService extends ServiceImpl<McpServerMapper, McpServerRecord> {

    private static final Set<String> SUPPORTED_TYPES = Set.of("http", "sse");
    private static final Set<String> SUPPORTED_VISIBILITIES = Set.of("public", "private");
    private static final Set<String> SUPPORTED_STATUSES = Set.of("online", "degraded", "offline", "unknown");
    private static final Set<String> SUPPORTED_NETWORK_ZONES = Set.of("internal", "office", "external");
    private static final Set<String> SENSITIVE_HEADER_KEYS = Set.of(
        "authorization", "proxy-authorization",
        "cookie", "set-cookie",
        "x-api-key", "apikey", "api-key",
        "token", "access-token", "refresh-token",
        "secret", "client-secret", "app-secret",
        "password", "passwd"
    );

    private final McpProperties mcpProperties;
    private final McpRequestContextService contextService;
    private final McpCryptoService cryptoService;

    public McpServerService(McpProperties mcpProperties,
                            McpRequestContextService contextService,
                            McpCryptoService cryptoService) {
        this.mcpProperties = mcpProperties;
        this.contextService = contextService;
        this.cryptoService = cryptoService;
    }

    @SuppressWarnings("unchecked")
    public McpServerRecord createMcpServer(Map<String, Object> request) {
        String userId = contextService.currentUserId();
        String userName = contextService.currentUserName();

        McpServerRecord record = new McpServerRecord();
        record.setMcpNo("MCP-" + System.currentTimeMillis());
        record.setName(normalizeRequiredText(request.get("name"), "MCP 名称不能为空"));
        record.setEndpoint(normalizeOptionalText(request.get("endpoint")));
        record.setDescription(normalizeOptionalText(request.get("description")));
        record.setVisibility(normalizeVisibility(request.getOrDefault("visibility", "private")));
        record.setStatus("unknown");
        record.setCreatorId(userId);
        record.setCreatorName(userName);

        record.setType(normalizeType(request.getOrDefault("type", "http")));
        record.setUrl(normalizeOptionalText(request.get("url")));
        record.setHealthCheckUrl(normalizeOptionalText(request.get("healthCheckUrl")));
        record.setVersion(normalizeOptionalText(request.get("version")));
        record.setNetworkZone(normalizeNetworkZone(request.getOrDefault("networkZone", "external")));
        record.setConsecutiveFailures(0);

        if (request.containsKey("headers")) {
            record.setHeaders((Map<String, String>) request.get("headers"));
        }
        if (request.containsKey("tags")) {
            record.setTags((List<String>) request.get("tags"));
        }
        if (request.containsKey("configJson")) {
            record.setConfigJson((Map<String, Object>) request.get("configJson"));
        }

        validateConnectivityFields(record);
        encryptSensitiveFields(record);
        this.save(record);
        decryptSensitiveFields(record);
        return record;
    }

    @SuppressWarnings("unchecked")
    public McpServerRecord updateMcpServer(Long id, Map<String, Object> request) {
        String userId = contextService.currentUserId();
        String userName = contextService.currentUserName();
        McpServerRecord record = requireOwnedMcpServer(id);

        if (request.containsKey("name")) {
            record.setName(normalizeRequiredText(request.get("name"), "MCP 名称不能为空"));
        }
        if (request.containsKey("endpoint")) {
            record.setEndpoint(normalizeOptionalText(request.get("endpoint")));
        }
        if (request.containsKey("description")) {
            record.setDescription(normalizeOptionalText(request.get("description")));
        }
        if (request.containsKey("visibility")) {
            record.setVisibility(normalizeVisibility(request.get("visibility")));
        }
        if (request.containsKey("status")) {
            record.setStatus(normalizeStatus(request.get("status")));
        }
        if (request.containsKey("configJson")) {
            record.setConfigJson((Map<String, Object>) request.get("configJson"));
        }

        if (request.containsKey("type")) {
            record.setType(normalizeType(request.get("type")));
        }
        if (request.containsKey("url")) {
            record.setUrl(normalizeOptionalText(request.get("url")));
        }
        if (request.containsKey("headers")) {
            record.setHeaders((Map<String, String>) request.get("headers"));
        }
        if (request.containsKey("healthCheckUrl")) {
            record.setHealthCheckUrl(normalizeOptionalText(request.get("healthCheckUrl")));
        }
        if (request.containsKey("version")) {
            record.setVersion(normalizeOptionalText(request.get("version")));
        }
        if (request.containsKey("tags")) {
            record.setTags((List<String>) request.get("tags"));
        }
        if (request.containsKey("networkZone")) {
            record.setNetworkZone(normalizeNetworkZone(request.get("networkZone")));
        }

        validateConnectivityFields(record);
        encryptSensitiveFields(record);
        record.setUpdaterId(userId);
        record.setUpdaterName(userName);
        this.updateById(record);
        decryptSensitiveFields(record);
        return record;
    }

    public List<McpServerRecord> listByTypes(List<String> types) {
        if (types == null || types.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<McpServerRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(McpServerRecord::getType, types);
        List<McpServerRecord> list = this.list(wrapper);
        list.forEach(this::decryptSensitiveFields);
        return list;
    }

    public void updateHealth(Long id, String status, Integer latencyMs, String message, int consecutiveFailures) {
        LambdaUpdateWrapper<McpServerRecord> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(McpServerRecord::getId, id)
            .set(McpServerRecord::getStatus, status)
            .set(McpServerRecord::getHealthLatencyMs, latencyMs)
            .set(McpServerRecord::getHealthMessage, message)
            .set(McpServerRecord::getConsecutiveFailures, consecutiveFailures)
            .set(McpServerRecord::getLastHealthAt, LocalDateTime.now());
        this.update(wrapper);
    }

    public Map<String, Object> generateMcpConfig(List<Long> mcpIds) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return Map.of("mcpServers", Collections.emptyMap());
        }

        List<McpServerRecord> servers = this.listByIds(mcpIds);
        servers.forEach(this::decryptSensitiveFields);
        Map<String, Object> mcpServers = new LinkedHashMap<>();

        boolean useGateway = StringUtils.hasText(mcpProperties.getGateway().getAgentBaseUrl());
        for (McpServerRecord server : servers) {
            Map<String, Object> serverConfig = new LinkedHashMap<>();
            serverConfig.put("type", server.getType() != null ? server.getType() : "http");

            if (useGateway) {
                String gatewayUrl = trimBaseUrl(mcpProperties.getGateway().getAgentBaseUrl())
                    + "/proxy/" + server.getName() + "/mcp";
                serverConfig.put("url", gatewayUrl);
            } else {
                String serverUrl = resolveUrl(server);
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

    public Map<String, Object> getHealthStatus() {
        String userId = contextService.currentUserId();
        boolean isAdmin = contextService.isCurrentUserAdmin();

        LambdaQueryWrapper<McpServerRecord> wrapper = new LambdaQueryWrapper<>();
        if (!isAdmin) {
            wrapper.and(w -> w.eq(McpServerRecord::getCreatorId, userId)
                .or().eq(McpServerRecord::getVisibility, "public"));
        }

        List<Map<String, Object>> items = this.list(wrapper).stream()
            .map(this::toHealthMap)
            .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("checkedAt", LocalDateTime.now());
        return result;
    }

    public Map<String, Object> listMcpServers(int page, int pageSize, String status, String keyword) {
        String userId = contextService.currentUserId();
        boolean isAdmin = contextService.isCurrentUserAdmin();
        Page<McpServerRecord> pageObj = new Page<>(page, pageSize);

        LambdaQueryWrapper<McpServerRecord> wrapper = new LambdaQueryWrapper<>();
        if (!isAdmin) {
            wrapper.and(w -> w.eq(McpServerRecord::getCreatorId, userId)
                .or().eq(McpServerRecord::getVisibility, "public"));
        }
        String normalizedStatus = normalizeStatusForQuery(status);
        if (StringUtils.hasText(normalizedStatus)) {
            wrapper.eq(McpServerRecord::getStatus, normalizedStatus);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(McpServerRecord::getName, keyword)
                .or().like(McpServerRecord::getDescription, keyword));
        }
        wrapper.orderByDesc(McpServerRecord::getCreatedAt);

        Page<McpServerRecord> result = this.page(pageObj, wrapper);
        result.getRecords().forEach(this::decryptSensitiveFields);

        List<Map<String, Object>> items = result.getRecords().stream()
            .map(record -> toResponseMap(record, userId, isAdmin))
            .collect(Collectors.toList());

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", result.getCurrent());
        pagination.put("pageSize", result.getSize());
        pagination.put("total", result.getTotal());
        pagination.put("totalPages", result.getPages());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("items", items);
        output.put("pagination", pagination);
        return output;
    }

    public List<Map<String, Object>> listAllAvailable() {
        String userId = contextService.currentUserId();
        boolean isAdmin = contextService.isCurrentUserAdmin();

        LambdaQueryWrapper<McpServerRecord> wrapper = new LambdaQueryWrapper<>();
        if (!isAdmin) {
            wrapper.and(w -> w.eq(McpServerRecord::getCreatorId, userId)
                .or().eq(McpServerRecord::getVisibility, "public"));
        }
        wrapper.orderByDesc(McpServerRecord::getCreatedAt);

        return this.list(wrapper).stream()
            .peek(this::decryptSensitiveFields)
            .map(record -> toSimpleMap(record, userId, isAdmin))
            .collect(Collectors.toList());
    }

    public Map<String, Object> getMcpServerForRead(Long id) {
        String userId = contextService.currentUserId();
        boolean isAdmin = contextService.isCurrentUserAdmin();
        McpServerRecord record = this.getById(id);
        if (record == null) {
            throw new IllegalArgumentException("MCP server not found: " + id);
        }
        if (!canRead(record, userId, isAdmin)) {
            throw new IllegalArgumentException("无权限访问该 MCP 服务");
        }
        decryptSensitiveFields(record);
        return toResponseMap(record, userId, isAdmin);
    }

    public McpServerRecord getMcpServerForManage(Long id) {
        McpServerRecord record = requireOwnedMcpServer(id);
        decryptSensitiveFields(record);
        return record;
    }

    public void deleteMcpServer(Long id) {
        McpServerRecord record = requireOwnedMcpServer(id);
        this.removeById(record.getId());
    }

    public List<Long> resolveIdsByRefs(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String ref : refs) {
            if (!StringUtils.hasText(ref)) {
                continue;
            }
            try {
                ids.add(Long.parseLong(ref));
            } catch (NumberFormatException ex) {
                names.add(ref);
            }
        }
        if (!names.isEmpty()) {
            LambdaQueryWrapper<McpServerRecord> byName = new LambdaQueryWrapper<>();
            byName.in(McpServerRecord::getName, names);
            this.list(byName).forEach(item -> ids.add(item.getId()));
        }
        return ids.stream().distinct().collect(Collectors.toList());
    }

    private McpServerRecord requireOwnedMcpServer(Long id) {
        McpServerRecord record = this.getById(id);
        if (record == null) {
            throw new IllegalArgumentException("MCP server not found: " + id);
        }
        String userId = contextService.currentUserId();
        boolean isAdmin = contextService.isCurrentUserAdmin();
        if (!canManage(record, userId, isAdmin)) {
            throw new IllegalArgumentException("仅 MCP 创建者或管理员可访问或修改");
        }
        return record;
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
        if (!StringUtils.hasText(rawStatus)) {
            return null;
        }
        return normalizeStatus(rawStatus);
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

    private void validateConnectivityFields(McpServerRecord record) {
        if (!StringUtils.hasText(record.getUrl()) && !StringUtils.hasText(record.getEndpoint())) {
            throw new IllegalArgumentException("MCP url/endpoint 不能为空");
        }
    }

    private void encryptSensitiveFields(McpServerRecord record) {
        if (!cryptoService.isEnabled() || record == null) {
            return;
        }
        if (StringUtils.hasText(record.getUrl())) {
            record.setUrl(cryptoService.encrypt(record.getUrl()));
        }
    }

    private void decryptSensitiveFields(McpServerRecord record) {
        if (!cryptoService.isEnabled() || record == null) {
            return;
        }
        if (StringUtils.hasText(record.getUrl())) {
            record.setUrl(cryptoService.decrypt(record.getUrl()));
        }
    }

    private boolean canRead(McpServerRecord record, String userId, boolean isAdmin) {
        if (isAdmin) {
            return true;
        }
        boolean isOwner = StringUtils.hasText(userId) && userId.equals(record.getCreatorId());
        boolean isPublic = "public".equals(coerceVisibility(record.getVisibility()));
        return isOwner || isPublic;
    }

    private boolean canManage(McpServerRecord record, String userId, boolean isAdmin) {
        return isAdmin || (StringUtils.hasText(userId) && userId.equals(record.getCreatorId()));
    }

    private boolean shouldMaskSensitiveFields(McpServerRecord record, String userId, boolean isAdmin) {
        return !canManage(record, userId, isAdmin) && "public".equals(coerceVisibility(record.getVisibility()));
    }

    private String coerceVisibility(String value) {
        if (!StringUtils.hasText(value)) {
            return "private";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_VISIBILITIES.contains(normalized) ? normalized : "private";
    }

    private String coerceStatus(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_STATUSES.contains(normalized) ? normalized : "unknown";
    }

    private Map<String, Object> toResponseMap(McpServerRecord record, String userId, boolean isAdmin) {
        boolean canManage = canManage(record, userId, isAdmin);
        boolean masked = shouldMaskSensitiveFields(record, userId, isAdmin);
        String urlForDisplay = firstNonBlank(record.getUrl(), record.getEndpoint());
        String healthUrlForDisplay = firstNonBlank(record.getHealthCheckUrl(), record.getUrl(), record.getEndpoint());

        Map<String, Object> map = new HashMap<>();
        map.put("id", String.valueOf(record.getId()));
        map.put("mcpNo", record.getMcpNo());
        map.put("name", record.getName());
        map.put("endpoint", masked ? null : record.getEndpoint());
        map.put("description", record.getDescription());
        map.put("visibility", coerceVisibility(record.getVisibility()));
        map.put("status", coerceStatus(record.getStatus()));
        map.put("type", record.getType());
        map.put("url", masked ? null : record.getUrl());
        map.put("headers", masked ? null : record.getHeaders());
        map.put("networkZone", record.getNetworkZone() != null ? record.getNetworkZone() : "external");
        map.put("healthCheckUrl", masked ? null : record.getHealthCheckUrl());
        map.put("displayUrl", maskUrlForDisplay(urlForDisplay));
        map.put("displayHeaders", maskHeadersForDisplay(record.getHeaders()));
        map.put("displayHealthCheckUrl", maskUrlForDisplay(healthUrlForDisplay));
        map.put("canManage", canManage);
        map.put("masked", masked);
        map.put("healthLatencyMs", record.getHealthLatencyMs());
        map.put("healthMessage", record.getHealthMessage());
        map.put("consecutiveFailures", record.getConsecutiveFailures());
        map.put("version", record.getVersion());
        map.put("tags", record.getTags());
        map.put("lastHealthAt", record.getLastHealthAt());
        map.put("configJson", record.getConfigJson());
        map.put("creatorId", record.getCreatorId());
        map.put("creatorName", record.getCreatorName());
        map.put("createdAt", record.getCreatedAt());
        map.put("updatedAt", record.getUpdatedAt());
        return map;
    }

    private Map<String, Object> toSimpleMap(McpServerRecord record, String userId, boolean isAdmin) {
        boolean canManage = canManage(record, userId, isAdmin);
        boolean masked = shouldMaskSensitiveFields(record, userId, isAdmin);
        String urlForDisplay = firstNonBlank(record.getUrl(), record.getEndpoint());

        Map<String, Object> map = new HashMap<>();
        map.put("id", String.valueOf(record.getId()));
        map.put("name", record.getName());
        map.put("description", record.getDescription());
        map.put("endpoint", masked ? null : record.getEndpoint());
        map.put("url", masked ? null : record.getUrl());
        map.put("displayUrl", maskUrlForDisplay(urlForDisplay));
        map.put("visibility", coerceVisibility(record.getVisibility()));
        map.put("status", coerceStatus(record.getStatus()));
        map.put("type", record.getType());
        map.put("networkZone", record.getNetworkZone() != null ? record.getNetworkZone() : "external");
        map.put("canManage", canManage);
        map.put("masked", masked);
        return map;
    }

    private Map<String, Object> toHealthMap(McpServerRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", String.valueOf(record.getId()));
        map.put("name", record.getName());
        map.put("type", record.getType());
        map.put("status", coerceStatus(record.getStatus()));
        map.put("latencyMs", record.getHealthLatencyMs());
        map.put("lastHealthAt", record.getLastHealthAt());
        map.put("consecutiveFailures", record.getConsecutiveFailures());
        map.put("healthMessage", record.getHealthMessage());
        return map;
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
        }
        return maskGenericValue(value);
    }

    private Map<String, String> maskHeadersForDisplay(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> masked = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (isSensitiveHeaderKey(lowerKey)) {
                masked.put(key, "***");
            } else {
                masked.put(key, maskGenericValue(entry.getValue()));
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

    private String maskGenericValue(String value) {
        if (!StringUtils.hasText(value)) {
            return "***";
        }
        String text = value.trim();
        if (text.length() <= 8) {
            return "***";
        }
        return text.substring(0, 3) + "***" + text.substring(text.length() - 2);
    }

    private String resolveUrl(McpServerRecord record) {
        if (StringUtils.hasText(record.getUrl())) {
            return record.getUrl();
        }
        return record.getEndpoint();
    }

    private String trimBaseUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.replaceAll("/+$", "");
    }
}
