package com.linkwork.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CronJobResponse {
    private Long id;
    private String jobName;
    private String creatorId;
    private String creatorName;
    private Long roleId;
    private String roleName;
    private String modelId;
    private String scheduleType;
    private String cronExpr;
    private Long intervalMs;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime runAt;
    private String timezone;
    private String taskContent;
    private Boolean enabled;
    private Boolean deleteAfterRun;
    private Integer maxRetry;
    private Integer consecutiveFailures;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime nextFireTime;
    private List<String> nextFireTimes;
    private String notifyMode;
    private String notifyTarget;
    private Integer totalRuns;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime lastRunTime;
    private String lastRunStatus;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
