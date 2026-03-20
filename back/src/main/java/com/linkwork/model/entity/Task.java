package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.linkwork.model.enums.TaskStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 任务实体
 * 对应数据库表: linkwork_task
 */
@Data
@TableName("linkwork_task")
public class Task {

    /**
     * 主键ID（自增）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 任务编号，格式: MSN-{yyyyMMddHHmmssSSS}
     * 对外展示使用此字段
     */
    private String taskNo;

    /**
     * 关联岗位ID
     */
    private Long roleId;

    /**
     * 岗位名称快照
     */
    private String roleName;

    private String prompt;

    private TaskStatus status;

    private String image;

    private String selectedModel;

    private Long assemblyId;

    private String configJson;

    /**
     * 任务来源：MANUAL / CRON
     */
    private String source;

    /**
     * 定时任务来源时关联 linkwork_cron_job.id
     */
    private Long cronJobId;

    private String creatorId;

    private String creatorName;

    private String creatorIp;

    private String updaterId;

    private String updaterName;

    private Integer tokensUsed;

    private Integer inputTokens;

    private Integer outputTokens;

    private Integer requestCount;

    private Long tokenLimit;

    private BigDecimal usagePercent;

    private Long durationMs;

    private String reportJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer isDeleted;
}
