package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TaskShareCreateRequest {

    @NotBlank
    private String taskNo;

    private Long expireHours;
}
