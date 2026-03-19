package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "linkwork_build_record", autoResultMap = true)
public class LinkworkBuildRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String buildNo;
    private Long roleId;
    private String roleName;
    private String status;
    private String imageTag;
    private Long durationMs;
    private String errorMessage;
    private String logUrl;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> configSnapshot;

    private String creatorId;
    private String creatorName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Boolean isDeleted;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_BUILDING = "BUILDING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
