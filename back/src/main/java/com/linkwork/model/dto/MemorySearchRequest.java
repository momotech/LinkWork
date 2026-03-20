package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MemorySearchRequest {
    @NotBlank
    private String query;
    private int topK = 10;
}
