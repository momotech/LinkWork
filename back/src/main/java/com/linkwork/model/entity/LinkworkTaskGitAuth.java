package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("linkwork_task_git_auth")
public class LinkworkTaskGitAuth {

    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;

    private String userId;

    private String provider;

    private Long gitAuthId;

    private LocalDateTime expiresAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Boolean isDeleted;
}
