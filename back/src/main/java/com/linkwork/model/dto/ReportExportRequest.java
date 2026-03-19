package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class ReportExportRequest {
    @NotBlank
    private String type;
    @NotBlank
    private String startTime;
    @NotBlank
    private String endTime;
    private List<String> fields;
    private Boolean includeEventStream;
}
