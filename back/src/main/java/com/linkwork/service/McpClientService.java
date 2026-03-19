package com.linkwork.service;

import com.linkwork.agent.mcp.core.McpClient;
import com.linkwork.agent.mcp.core.model.McpDiscoverResponse;
import com.linkwork.agent.mcp.core.model.McpEndpoint;
import com.linkwork.agent.mcp.core.model.McpProbeResponse;
import com.linkwork.agent.mcp.core.model.McpToolCallResponse;
import com.linkwork.model.dto.McpEndpointRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

@Service
public class McpClientService {

    private final McpClient mcpClient;

    public McpClientService(McpClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    public McpDiscoverResponse discover(McpEndpointRequest request) {
        return mcpClient.discover(toEndpoint(request));
    }

    public McpProbeResponse probe(McpEndpointRequest request) {
        return mcpClient.probe(toEndpoint(request));
    }

    public McpToolCallResponse callTool(McpEndpointRequest request, String toolName, Map<String, Object> arguments) {
        return mcpClient.callTool(toEndpoint(request), toolName, arguments);
    }

    private McpEndpoint toEndpoint(McpEndpointRequest request) {
        McpEndpoint endpoint = new McpEndpoint();
        if (request == null) {
            throw new IllegalArgumentException("endpoint is required");
        }

        endpoint.setType(request.getType());
        endpoint.setUrl(request.getUrl());
        endpoint.setHeaders(request.getHeaders());
        endpoint.setCommand(request.getCommand());
        endpoint.setEnv(request.getEnv());

        validateEndpoint(endpoint);
        return endpoint;
    }

    private void validateEndpoint(McpEndpoint endpoint) {
        String endpointType = StringUtils.hasText(endpoint.getType())
            ? endpoint.getType().trim().toLowerCase(Locale.ROOT)
            : "sse";

        if ("stdio".equals(endpointType)) {
            if (CollectionUtils.isEmpty(endpoint.getCommand())) {
                throw new IllegalArgumentException("stdio endpoint requires command");
            }
            return;
        }

        if (!StringUtils.hasText(endpoint.getUrl())) {
            throw new IllegalArgumentException("endpoint url is required");
        }
    }
}
