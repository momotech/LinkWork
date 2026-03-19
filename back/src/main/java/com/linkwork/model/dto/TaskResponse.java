package com.linkwork.model.dto;

import com.linkwork.model.enums.TaskStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class TaskResponse {

    private Long id;
    private String taskNo;
    private Long roleId;
    private String roleName;
    private String prompt;
    private TaskStatus status;
    private String image;
    private String modelId;
    private String selectedModel;
    private String runtimeMode;
    private String zzMode;
    private String runnerImage;
    private Long assemblyId;
    private Object configJson;
    private String source;
    private Long cronJobId;
    private String creatorId;
    private String creatorName;

    private Integer tokensUsed;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer requestCount;
    private Long tokenLimit;
    private BigDecimal usagePercent;
    private Long durationMs;
    private Object reportJson;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<FileResponse> files;
}
