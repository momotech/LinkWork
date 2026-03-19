package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("linkwork_user_favorite_workstation")
public class UserFavoriteWorkstationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    private Long workstationId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
