package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "linkwork_mcp_user_config", autoResultMap = true)
public class McpUserConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    private Long mcpServerId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, String> headers;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, String> urlParams;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Boolean isDeleted;
}
