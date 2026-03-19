package com.linkwork.model.mcp.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class McpDiscoverResult {

    private boolean success;
    private String error;
    private String serverName;
    private String serverVersion;
    private String protocolVersion;
    private List<McpTool> tools = new ArrayList<>();

    public static McpDiscoverResult failure(String error) {
        McpDiscoverResult result = new McpDiscoverResult();
        result.success = false;
        result.error = error;
        return result;
    }

    public static McpDiscoverResult success(String serverName,
                                            String serverVersion,
                                            String protocolVersion,
                                            List<McpTool> tools) {
        McpDiscoverResult result = new McpDiscoverResult();
        result.success = true;
        result.serverName = serverName;
        result.serverVersion = serverVersion;
        result.protocolVersion = protocolVersion;
        result.tools = tools == null ? new ArrayList<>() : new ArrayList<>(tools);
        return result;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public List<McpTool> getTools() {
        return tools;
    }

    public void setTools(List<McpTool> tools) {
        this.tools = tools == null ? new ArrayList<>() : new ArrayList<>(tools);
    }

    public static class McpTool {
        private String name;
        private String description;
        private Map<String, Object> inputSchema = new LinkedHashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public Map<String, Object> getInputSchema() {
            return inputSchema;
        }

        public void setInputSchema(Map<String, Object> inputSchema) {
            this.inputSchema = inputSchema == null ? new LinkedHashMap<>() : new LinkedHashMap<>(inputSchema);
        }
    }
}
