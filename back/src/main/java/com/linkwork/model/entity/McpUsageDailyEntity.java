package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("linkwork_mcp_usage_daily")
public class McpUsageDailyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate date;

    private String userId;

    private String mcpName;

    private Integer callCount;

    private Long reqBytes;

    private Long respBytes;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
