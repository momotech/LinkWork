package com.linkwork.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileMentionResponse {
    private String fileId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String spaceType;
    private String workstationId;
    private LocalDateTime createdAt;
}
