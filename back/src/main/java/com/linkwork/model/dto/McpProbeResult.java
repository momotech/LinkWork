package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * MCP Server 探活结果 DTO
 */
@Data
@Builder
public class McpProbeResult {

    /**
     * 健康状态：online / degraded / offline
     */
    private String status;

    /**
     * 探活延迟（毫秒）
     */
    private int latencyMs;

    /**
     * 探活结果描述，例如 "HTTP 200 (123ms)"
     */
    private String message;

    /**
     * 实际探测的 URL
     */
    private String probeUrl;
}
