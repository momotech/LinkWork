package com.linkwork.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CronJobToggleRequest {
    @NotNull(message = "enabled 不能为空")
    private Boolean enabled;
}
