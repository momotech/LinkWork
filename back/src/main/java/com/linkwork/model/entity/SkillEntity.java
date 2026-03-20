package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能实体类
 */
@Data
@TableName(value = "linkwork_skill", autoResultMap = true)
public class SkillEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 技能编号，格式: SKL-{timestamp} */
    private String skillNo;

    /** 技能标识名（唯一） */
    private String name;

    /** 显示名称 */
    private String displayName;

    /** 技能描述 */
    private String description;

    /** 技能实现代码 */
    private String implementation;

    /** 状态: draft/ready/disabled */
    private String status;

    /** 是否公开 */
    private Boolean isPublic;

    /** Git 分支名称 */
    private String branchName;

    /** 最新 commit SHA */
    private String latestCommit;

    /** 最后同步时间 */
    private LocalDateTime lastSyncedAt;

    private String creatorId;

    private String creatorName;

    private String updaterId;

    private String updaterName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Boolean isDeleted;
}
