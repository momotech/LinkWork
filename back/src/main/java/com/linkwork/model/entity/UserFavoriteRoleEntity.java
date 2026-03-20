package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("linkwork_user_favorite_workstation")
public class UserFavoriteRoleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    private Long roleId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
