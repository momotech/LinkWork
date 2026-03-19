package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class TaskCreateRequest {

    @NotNull
    private Long workstationId;

    @NotBlank
    private String prompt;

    private String selectedModel;

    private String source;

    private Long assemblyId;

    private List<Long> fileIds;

    private Object configJson;
}
