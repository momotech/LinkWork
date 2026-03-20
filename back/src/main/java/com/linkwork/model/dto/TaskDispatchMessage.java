package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 任务调度消息 DTO
 * 用于 Redis 队列传输
 */
@Data
@Builder
public class TaskDispatchMessage {

    /**
     * 任务编号
     */
    private String taskNo;

    /**
     * 岗位 ID
     */
    private Long roleId;

    /**
     * 岗位名称
     */
    private String roleName;

    /**
     * 任务指令
     */
    private String prompt;

    /**
     * 任务配置
     */
    private TaskConfig config;

    /**
     * 创建者 ID
     */
    private String creatorId;

    /**
     * 创建者名称
     */
    private String creatorName;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 任务配置
     */
    @Data
    @Builder
    public static class TaskConfig {
        private String image;
        private String selectedModel;
        private List<String> mcp;
        private List<String> skills;
        private List<String> knowledge;
        private List<GitRepo> gitRepos;
        private Map<String, String> env;
    }

    @Data
    @Builder
    public static class GitRepo {
        private String id;
        private String branch;
    }
}
