package com.linkwork.model.dto.event;

import lombok.Data;

@Data
public class BuildEventData {
    private String buildId;
    private String status;
    private String logLine;
    private Integer progress;
    private String imageName;
    private String imageTag;
    private String message;
}
