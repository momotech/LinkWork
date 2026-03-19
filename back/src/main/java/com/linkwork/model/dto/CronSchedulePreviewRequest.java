package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CronSchedulePreviewRequest {
    @NotBlank
    private String cronExpr;
    private String timezone;
    private Integer count;
}
