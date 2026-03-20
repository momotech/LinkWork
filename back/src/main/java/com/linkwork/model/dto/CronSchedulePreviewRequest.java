package com.linkwork.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CronSchedulePreviewRequest {
    private String scheduleType;
    private String cronExpr;
    private Long intervalMs;
    private LocalDateTime runAt;
    private String timezone;
    private Integer limit;
}
