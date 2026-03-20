package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("linkwork_user_auth_gitlab")
public class GitLabAuthEntity {

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String userId;

    private Long gitlabId;

    private String username;

    private String name;

    private String avatarUrl;

    private String accessToken;

    private String refreshToken;

    private String tokenAlias;

    private LocalDateTime expiresAt;

    private String scope;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Boolean isDeleted;
}
