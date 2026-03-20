package com.linkwork.service;

import com.linkwork.agent.mcp.core.McpClient;
import com.linkwork.agent.mcp.core.model.McpDiscoverResponse;
import com.linkwork.agent.mcp.core.model.McpEndpoint;
import com.linkwork.model.dto.McpDiscoverResult;
import com.linkwork.model.entity.McpServerEntity;
import com.linkwork.model.entity.McpUserConfigEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具发现服务（基于 linkwork-mcp-starter）。
 */
@Service
@Slf4j
public class McpDiscoveryService {

    private final McpClient mcpClient;
    private final McpUserConfigService mcpUserConfigService;

    public McpDiscoveryService(McpClient mcpClient, McpUserConfigService mcpUserConfigService) {
        this.mcpClient = mcpClient;
        this.mcpUserConfigService = mcpUserConfigService;
    }

    public McpDiscoverResult discover(McpServerEntity server) {
        return discover(server, null);
    }

    public McpDiscoverResult discover(McpServerEntity server, String userId) {
        DiscoveryTarget target = resolveDiscoveryTarget(server, userId);
        if (!StringUtils.hasText(target.getUrl())) {
            return McpDiscoverResult.builder()
                    .success(false)
                    .error("No URL configured for MCP server")
                    .build();
        }

        try {
            McpEndpoint endpoint = toEndpoint(server, target);
            McpDiscoverResponse response = mcpClient.discover(endpoint);
            if (response == null || !response.isSuccess()) {
                return McpDiscoverResult.builder()
                        .success(false)
                        .error(response == null ? "discover response is null" : response.getMessage())
                        .build();
            }

            List<McpDiscoverResult.McpTool> tools = new ArrayList<>();
            if (response.getTools() != null) {
                response.getTools().forEach(tool -> tools.add(McpDiscoverResult.McpTool.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .inputSchema(tool.getInputSchema())
                        .build()));
            }

            return McpDiscoverResult.builder()
                    .success(true)
                    .error(null)
                    .serverName(response.getServerName())
                    .serverVersion(response.getServerVersion())
                    .protocolVersion(response.getProtocolVersion())
                    .tools(tools)
                    .build();
        } catch (Exception e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (error.length() > 500) {
                error = error.substring(0, 500);
            }
            log.error("MCP discover failed for {}: {}", server.getName(), error);
            return McpDiscoverResult.builder()
                    .success(false)
                    .error(error)
                    .build();
        }
    }

    DiscoveryTarget resolveDiscoveryTarget(McpServerEntity server, String userId) {
        String serverUrl = resolveUrl(server);
        Map<String, String> mergedHeaders = new LinkedHashMap<>();
        if (server.getHeaders() != null) {
            mergedHeaders.putAll(server.getHeaders());
        }

        if (StringUtils.hasText(userId) && server.getId() != null) {
            McpUserConfigEntity userConfig = mcpUserConfigService.getByUserAndServer(userId, server.getId());
            if (userConfig != null) {
                mergePreferredValues(mergedHeaders, userConfig.getHeaders());
                serverUrl = applyUrlParams(serverUrl, userConfig.getUrlParams());
            }
        }

        return new DiscoveryTarget(serverUrl, mergedHeaders);
    }

    private McpEndpoint toEndpoint(McpServerEntity server, DiscoveryTarget target) {
        McpEndpoint endpoint = new McpEndpoint();
        endpoint.setType(StringUtils.hasText(server.getType()) ? server.getType() : "http");
        endpoint.setUrl(target.getUrl());
        endpoint.setHeaders(target.getHeaders());

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

    private String resolveUrl(McpServerEntity server) {
        if (StringUtils.hasText(server.getUrl())) {
            return server.getUrl();
        }
        return server.getEndpoint();
    }

    private void mergePreferredValues(Map<String, String> base, Map<String, String> preferred) {
        if (preferred == null || preferred.isEmpty()) {
            return;
        }
        preferred.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                base.put(key, value);
            }
        });
    }

    private String applyUrlParams(String baseUrl, Map<String, String> urlParams) {
        if (!StringUtils.hasText(baseUrl) || urlParams == null || urlParams.isEmpty()) {
            return baseUrl;
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl);
        urlParams.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                builder.replaceQueryParam(key, value);
            }
        });
        return builder.build().toUriString();
    }

    static final class DiscoveryTarget {
        private final String url;
        private final Map<String, String> headers;

        DiscoveryTarget(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers != null
                    ? Collections.unmodifiableMap(new LinkedHashMap<>(headers))
                    : Collections.emptyMap();
        }

        String getUrl() {
            return url;
        }

        Map<String, String> getHeaders() {
            return headers;
        }
    }
}
