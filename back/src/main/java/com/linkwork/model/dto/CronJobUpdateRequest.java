package com.linkwork.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CronJobUpdateRequest {
    private String jobName;
    private String modelId;
    private String taskContent;
    private List<Long> fileIds;
    private String scheduleType;
    private String cronExpr;
    private Long intervalMs;
    private LocalDateTime runAt;
    private String timezone;
    private Integer maxRetry;
    private Integer deleteAfterRun;
    private String notifyMode;
    private String notifyTarget;
}
