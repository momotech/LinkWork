package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("linkwork_user_soul")
public class LinkworkUserSoul {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String content;
    private String presetId;
    private Long version;
    private String creatorId;
    private String creatorName;
    private String updaterId;
    private String updaterName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer isDeleted;
}
