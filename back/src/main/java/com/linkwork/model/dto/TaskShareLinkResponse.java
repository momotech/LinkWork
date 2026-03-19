package com.linkwork.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TaskShareLinkResponse {
    private String shareToken;
    private String taskNo;
    private String shareUrl;
    private LocalDateTime expiredAt;
}
