package com.linkwork.service.mcp;

import com.linkwork.agent.mcp.core.McpClient;
import com.linkwork.agent.mcp.core.model.McpDiscoverResponse;
import com.linkwork.agent.mcp.core.model.McpEndpoint;
import com.linkwork.model.mcp.dto.McpDiscoverResult;
import com.linkwork.model.mcp.McpServerRecord;
import com.linkwork.model.mcp.McpUserConfigRecord;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class McpDiscoveryService {

    private final McpClient mcpClient;
    private final McpUserConfigService mcpUserConfigService;
    private final McpRequestContextService contextService;

    public McpDiscoveryService(McpClient mcpClient,
                               McpUserConfigService mcpUserConfigService,
                               McpRequestContextService contextService) {
        this.mcpClient = mcpClient;
        this.mcpUserConfigService = mcpUserConfigService;
        this.contextService = contextService;
    }

    public McpDiscoverResult discover(McpServerRecord server) {
        return discover(server, contextService.currentUserId());
    }

    public McpDiscoverResult discover(McpServerRecord server, String userId) {
        DiscoveryTarget target = resolveDiscoveryTarget(server, userId);
        if (!StringUtils.hasText(target.url())) {
            return McpDiscoverResult.failure("No URL configured for MCP server");
        }

        McpEndpoint endpoint = new McpEndpoint();
        endpoint.setType(server.getType());
        endpoint.setUrl(target.url());
        endpoint.setHeaders(target.headers());
        McpDiscoverResponse response = mcpClient.discover(endpoint);

        if (!response.isSuccess()) {
            return McpDiscoverResult.failure(response.getMessage());
        }

        List<McpDiscoverResult.McpTool> tools = new ArrayList<>();
        response.getTools().forEach(tool -> {
            McpDiscoverResult.McpTool output = new McpDiscoverResult.McpTool();
            output.setName(tool.getName());
            output.setDescription(tool.getDescription());
            output.setInputSchema(tool.getInputSchema());
            tools.add(output);
        });
        return McpDiscoverResult.success(response.getServerName(), response.getServerVersion(), response.getProtocolVersion(), tools);
    }

    DiscoveryTarget resolveDiscoveryTarget(McpServerRecord server, String userId) {
        String serverUrl = resolveUrl(server);
        Map<String, String> mergedHeaders = new LinkedHashMap<>();
        if (server.getHeaders() != null) {
            mergedHeaders.putAll(server.getHeaders());
        }

        if (StringUtils.hasText(userId) && server.getId() != null) {
            McpUserConfigRecord userConfig = mcpUserConfigService.getByUserAndServer(userId, server.getId());
            if (userConfig != null) {
                mergePreferredValues(mergedHeaders, userConfig.getHeaders());
                serverUrl = applyUrlParams(serverUrl, userConfig.getUrlParams());
            }
        }
        return new DiscoveryTarget(serverUrl, mergedHeaders);
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

    private String resolveUrl(McpServerRecord server) {
        if (StringUtils.hasText(server.getUrl())) {
            return server.getUrl();
        }
        return server.getEndpoint();
    }

    record DiscoveryTarget(String url, Map<String, String> headers) {
    }
}
