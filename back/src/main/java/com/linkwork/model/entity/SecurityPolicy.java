package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 安全策略实体
 * 对应数据库表: linkwork_security_policy
 */
@Data
@TableName("linkwork_security_policy")
public class SecurityPolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略名称 */
    private String name;

    /** 策略描述 */
    private String description;

    /** 类型: system/custom */
    private String type;

    /** 是否启用 */
    private Boolean enabled;

    /** 规则列表 JSON */
    private String rulesJson;

    private String creatorId;

    private String creatorName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer isDeleted;
}
