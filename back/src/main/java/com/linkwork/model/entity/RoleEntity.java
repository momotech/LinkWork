package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@TableName(value = "linkwork_workstation", autoResultMap = true)
public class RoleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String roleNo;

    private String name;

    private String description;

    private String category; // devops, security, developer, research

    private String icon;

    private String image;

    private String prompt;

    private String status; // active, maintenance, disabled

    @TableField(typeHandler = JacksonTypeHandler.class)
    private RoleConfig configJson;

    private Boolean isPublic;

    private Integer maxEmployees;

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

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RoleConfig {
        private List<String> mcp; // List of IDs
        private List<String> skills; // List of IDs/Names
        private List<String> knowledge; // List of IDs/Names
        private String deployMode; // K8S / COMPOSE
        private String runtimeMode; // SIDECAR / ALONE
        private String runnerImage; // 仅 SIDECAR 生效
        private Boolean memoryEnabled; // 岗位级记忆开关
        private List<GitRepo> gitRepos;
        private List<EnvVar> env;

        @Data
        public static class GitRepo {
            private String url;
            private String branch;
        }

        @Data
        public static class EnvVar {
            private String key;
            private String value;
        }
    }
}
