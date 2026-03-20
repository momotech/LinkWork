package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("linkwork_user_soul")
public class UserSoulEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    @TableField("soul")
    private String content;

    @TableField("template_id")
    private String presetId;

    @TableField(exist = false)
    private Long version;

    private String creatorId;

    private String creatorName;

    @TableField(exist = false)
    private String updaterId;

    @TableField(exist = false)
    private String updaterName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer isDeleted;
}
