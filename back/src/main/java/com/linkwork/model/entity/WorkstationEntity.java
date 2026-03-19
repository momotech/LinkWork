package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "linkwork_workstation", autoResultMap = true)
public class WorkstationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String workstationNo;

    private String name;

    private String description;

    private String category;

    private String icon;

    private String image;

    private String prompt;

    private String status;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private WorkstationConfig configJson;

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
    public static class WorkstationConfig {
        private List<String> mcp;
        private List<String> skills;
        private List<String> knowledge;
        private String deployMode;
        private String runtimeMode;
        private String runnerImage;
        private Boolean memoryEnabled;
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
