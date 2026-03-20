package com.linkwork.model.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class MemoryIndexBatchRequest {
    @NotEmpty
    private List<String> filePaths;
}
