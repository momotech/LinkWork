package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MemoryIngestRequest {
    @NotBlank
    private String content;
    private String source = "";
}
