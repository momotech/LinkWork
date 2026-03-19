package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.linkwork.model.enums.TaskStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("linkwork_task")
public class LinkworkTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskNo;

    private Long workstationId;

    private String workstationName;

    private String prompt;

    private TaskStatus status;

    private String image;

    private String selectedModel;

    private Long assemblyId;

    private String configJson;

    private String source;

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
