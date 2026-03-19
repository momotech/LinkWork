package com.linkwork.model.dto;

import lombok.Data;

@Data
public class TaskGitTokenResponse {
    private String token;
    private String provider;
    private String username;
}
