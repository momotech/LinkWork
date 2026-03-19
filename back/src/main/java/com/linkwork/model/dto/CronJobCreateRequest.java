package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CronJobCreateRequest {

    @NotBlank
    private String jobName;

    @NotNull
    private Long workstationId;

    private String modelId;

    @NotBlank
    private String taskContent;

    private List<Long> fileIds;

    @NotBlank
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
