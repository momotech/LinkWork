package com.linkwork.model.dto;

import lombok.Data;

/**
 * zzd 获取任务 Git token 响应
 */
@Data
public class TaskGitTokenResponse {

    private String provider;

    private String tokenType;

    private String token;

    private String expiresAt;

    /**
     * git commit 需要的提交身份（由 token 对应 Git 用户解析）
     */
    private String commitUserName;

    private String commitUserEmail;
}
