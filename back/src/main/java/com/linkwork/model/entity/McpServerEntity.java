package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务实体类
 */
@Data
@TableName(value = "linkwork_mcp_server", autoResultMap = true)
public class McpServerEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** MCP编号，格式: MCP-{timestamp} */
    private String mcpNo;

    /** 服务名称 */
    private String name;

    /** 服务端点地址 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String endpoint;

    /** 服务描述 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    /** 可见性: public/private */
    private String visibility;

    /** 状态: online/offline/degraded/unknown */
    private String status;

    /** MCP类型: http/sse */
    private String type;

    /** 服务URL (http/sse) */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String url;

    /** HTTP请求头 JSON */
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private Map<String, String> headers;

    /** 网段标记: internal(服务器内网), office(办公网), external(外部互联网) */
    private String networkZone;

    /** 健康检查URL */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String healthCheckUrl;

    /** 最近探活延迟(ms) */
    private Integer healthLatencyMs;

    /** 最近探活消息 */
    private String healthMessage;

    /** 连续失败次数 */
    private Integer consecutiveFailures;

    /** 版本号 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String version;

    /** 标签 */
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private List<String> tags;

    /** 最后健康检查时间 */
    private LocalDateTime lastHealthAt;

    /** 服务配置 JSON */
    @TableField(typeHandler = JacksonTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS)
    private Map<String, Object> configJson;

    private String creatorId;

    private String creatorName;

    private String updaterId;

    private String updaterName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Boolean isDeleted;
}
