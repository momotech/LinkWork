package com.linkwork.model.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务分享链接响应
 */
@Data
public class TaskShareLinkResponse {
    private String taskId;
    private String token;
    private String shareUrl;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime expiresAt;
}
