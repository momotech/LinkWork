package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批实体
 * 对应数据库表: linkwork_approval
 */
@Data
@TableName("linkwork_approval")
public class Approval {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 审批编号 */
    private String approvalNo;

    /** 关联任务编号 */
    private String taskNo;

    /** momo-worker 审批请求 ID（用于 Redis List 回写响应） */
    private String requestId;

    /** 任务标题快照 */
    private String taskTitle;

    /** 待审批操作内容 */
    private String action;

    /** 风险描述 */
    private String description;

    /** 风险等级: low/medium/high/critical */
    private String riskLevel;

    /** 状态: pending/approved/rejected/expired */
    private String status;

    /** 决策: approved/rejected */
    private String decision;

    /** 审批意见 */
    private String comment;

    /** 审批操作人ID */
    private String operatorId;

    /** 审批操作人名称 */
    private String operatorName;

    /** 审批操作来源IP */
    private String operatorIp;

    /** 过期时间 */
    private LocalDateTime expiredAt;

    /** 决策时间 */
    private LocalDateTime decidedAt;

    /** 创建人ID */
    private String creatorId;

    /** 创建人名称 */
    private String creatorName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer isDeleted;
}
