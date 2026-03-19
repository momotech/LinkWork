package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("linkwork_approval")
public class LinkworkApproval {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String approvalNo;

    private String taskNo;

    private String requestId;

    private String taskTitle;

    private String action;

    private String description;

    private String riskLevel;

    private String status;

    private String decision;

    private String comment;

    private String operatorId;

    private String operatorName;

    private String operatorIp;

    private LocalDateTime expiredAt;

    private LocalDateTime decidedAt;

    private String creatorId;

    private String creatorName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer isDeleted;
}
