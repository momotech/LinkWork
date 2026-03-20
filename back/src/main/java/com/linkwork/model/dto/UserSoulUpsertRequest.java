package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserSoulUpsertRequest {

    @NotBlank(message = "Soul 内容不能为空")
    private String content;

    private String presetId;

    @NotNull(message = "version 不能为空")
    private Long version;
}
