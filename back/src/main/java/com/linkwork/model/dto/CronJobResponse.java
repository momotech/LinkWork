package com.linkwork.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CronJobResponse {
    private Long id;
    private String jobName;
    private String creatorId;
    private String creatorName;
    private Long workstationId;
    private String workstationName;
    private String modelId;
    private String fileIdsJson;
    private String scheduleType;
    private String cronExpr;
    private Long intervalMs;
    private LocalDateTime runAt;
    private String timezone;
    private String taskContent;
    private Boolean enabled;
    private Boolean deleteAfterRun;
    private Integer maxRetry;
    private Integer consecutiveFailures;
    private LocalDateTime nextFireTime;
    private String notifyMode;
    private String notifyTarget;
    private Integer totalRuns;
    private LocalDateTime lastRunTime;
    private String lastRunStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
