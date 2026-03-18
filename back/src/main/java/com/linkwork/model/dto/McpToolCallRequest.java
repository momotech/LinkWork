package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.LinkedHashMap;
import java.util.Map;

public class McpToolCallRequest {

    private McpEndpointRequest endpoint = new McpEndpointRequest();

    @NotBlank(message = "toolName is required")
    private String toolName;

    private Map<String, Object> arguments = new LinkedHashMap<>();

    public McpEndpointRequest getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(McpEndpointRequest endpoint) {
        this.endpoint = endpoint;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
    }
}
