package com.linkwork.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileNodeResponse {
    private String nodeId;
    private String parentId;
    private String entryType;
    private String name;
    private String spaceType;
    private String workstationId;
    private String fileId;
    private Long fileSize;
    private String fileType;
    private String parseStatus;
    private String memoryIndexStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean hasChildren;
}
