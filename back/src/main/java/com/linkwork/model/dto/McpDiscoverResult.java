package com.linkwork.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * MCP Server 工具发现结果 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpDiscoverResult {

    /** 是否成功 */
    private boolean success;

    /** 失败原因 */
    private String error;

    /** MCP server 返回的名称 */
    private String serverName;

    /** MCP server 返回的版本 */
    private String serverVersion;

    /** MCP 协议版本 */
    private String protocolVersion;

    /** 工具列表 */
    private List<McpTool> tools;

    /**
     * MCP 工具定义
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class McpTool {

        /** 工具名称 */
        private String name;

        /** 工具描述 */
        private String description;

        /** 输入参数的 JSON Schema */
        private Map<String, Object> inputSchema;
    }
}
