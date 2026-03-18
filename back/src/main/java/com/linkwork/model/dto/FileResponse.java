package com.linkwork.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileResponse {
    private String fileId;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String contentType;
    private String spaceType;
    private String workstationId;
    private String parseStatus;
    private String memoryIndexStatus;
    private LocalDateTime createdAt;
}
