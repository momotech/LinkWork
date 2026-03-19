package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class TaskCreateRequest {

    @NotNull
    private Long roleId;

    @NotBlank
    private String prompt;

    @NotBlank
    private String modelId;

    private String source;

    private Long assemblyId;

    private List<Long> fileIds;

    private Object configJson;
}
