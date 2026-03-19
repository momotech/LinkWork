package com.linkwork.model.dto.event;

import lombok.Data;

import java.util.Map;

@Data
public class InitCompleteData {
    private String streamUrl;
    private String logPath;
    private Map<String, String> filePathMappings;
    private Map<String, String> envVars;
}
