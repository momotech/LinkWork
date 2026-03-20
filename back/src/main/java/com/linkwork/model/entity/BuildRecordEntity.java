package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 构建记录实体
 * 记录每次镜像构建的完整信息
 */
@Data
@TableName(value = "linkwork_build_record", autoResultMap = true)
public class BuildRecordEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 构建唯一编号，格式: build-{timestamp}-{random}
     * 由前端生成并传入，用于关联 Redis Stream 事件
     */
    private String buildNo;

    /**
     * 关联的岗位 ID
     */
    private Long roleId;

    /**
     * 岗位名称快照（构建时的岗位名称）
     */
    private String roleName;

    /**
     * 构建状态: PENDING, BUILDING, SUCCESS, FAILED, CANCELLED
     */
    private String status;

    /**
     * 构建产物镜像标签
     */
    private String imageTag;

    /**
     * 构建耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 失败原因
     */
    private String errorMessage;

    /**
     * 构建日志文件 URL (OSS)
     */
    private String logUrl;

    /**
     * 构建时的配置快照（JSON 格式）
     * 包含: baseImage, envVars, mcpModules, skills, knowledgeBases 等
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> configSnapshot;

    /**
     * 创建者 ID
     */
    private String creatorId;

    /**
     * 创建者名称
     */
    private String creatorName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Boolean isDeleted;

    // ========== 状态常量 ==========
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_BUILDING = "BUILDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
