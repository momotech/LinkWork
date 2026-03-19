package com.linkwork.model.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class TaskDispatchMessage {
    private String taskNo;
    private Long workstationId;
    private String workstationName;
    private String prompt;
    private String selectedModel;
    private String creatorId;
    private String creatorName;
    private String image;
    private Long assemblyId;
    private Object configJson;
    private List<Long> fileIds;
    private Map<String, String> filePathMappings;
    private Map<String, String> envVars;
    private String callbackUrl;
}
