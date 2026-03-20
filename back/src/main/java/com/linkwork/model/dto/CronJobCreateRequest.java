package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
@Data
public class CronJobCreateRequest {
    @NotBlank(message = "任务名称不能为空")
    private String jobName;

    @NotNull(message = "岗位ID不能为空")
    private Long roleId;

    @NotBlank(message = "模型ID不能为空")
    private String modelId;

    @NotBlank(message = "调度类型不能为空")
    private String scheduleType;

    private String cronExpr;
    private Long intervalMs;
    private LocalDateTime runAt;
    private String timezone;

    @NotBlank(message = "任务指令不能为空")
    @Size(max = 10000, message = "任务指令长度不能超过10000字符")
    private String taskContent;

    private Boolean deleteAfterRun;
    private Integer maxRetry;
    private String notifyMode;
    private String notifyTarget;
}
