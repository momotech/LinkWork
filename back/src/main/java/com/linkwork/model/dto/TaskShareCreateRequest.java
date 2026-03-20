package com.linkwork.model.dto;

import lombok.Data;

/**
 * 创建任务分享链接请求
 */
@Data
public class TaskShareCreateRequest {

    /**
     * 过期时长（小时）
     */
    private Integer expireHours;
}
