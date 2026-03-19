package com.linkwork.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CronJobRunResponse {
    private Long id;
    private Long cronJobId;
    private String taskNo;
    private String creatorId;
    private Long workstationId;
    private String status;
    private String triggerType;
    private LocalDateTime plannedFireTime;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String errorMessage;
    private LocalDateTime createdAt;
}
