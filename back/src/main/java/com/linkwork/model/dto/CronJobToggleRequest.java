package com.linkwork.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CronJobToggleRequest {
    @NotNull
    private Boolean enabled;
}
