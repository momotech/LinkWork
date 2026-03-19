package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserSoulUpsertRequest {
    @NotBlank
    private String content;
    private String presetId;
    private Long version = 0L;
}
