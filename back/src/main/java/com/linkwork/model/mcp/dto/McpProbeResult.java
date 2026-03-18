package com.linkwork.model.mcp.dto;

public class McpProbeResult {

    private String status;
    private int latencyMs;
    private String message;
    private String probeUrl;

    public static McpProbeResult of(String status, int latencyMs, String message, String probeUrl) {
        McpProbeResult result = new McpProbeResult();
        result.status = status;
        result.latencyMs = latencyMs;
        result.message = message;
        result.probeUrl = probeUrl;
        return result;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(int latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getProbeUrl() {
        return probeUrl;
    }

    public void setProbeUrl(String probeUrl) {
        this.probeUrl = probeUrl;
    }
}
