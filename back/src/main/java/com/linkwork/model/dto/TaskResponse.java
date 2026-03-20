package com.linkwork.model.dto;

import com.linkwork.model.enums.TaskStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 任务响应 DTO
 */
@Data
public class TaskResponse {

    /**
     * 任务编号（对外展示）
     */
    private String taskNo;

    /**
     * 关联岗位 ID
     */
    private Long roleId;

    /**
     * 岗位名称
     */
    private String roleName;

    private String prompt;

    private TaskStatus status;

    private String source;

    private String image;

    private String selectedModel;

    private String creator;

    private String createdAt;

    private Usage usage;

    private List<String> estimatedOutput;

    private Report report;

    // ---- 从 configJson 解析的字段 ----
    private String runtimeMode;
    private String zzMode;
    private String runnerImage;
    private String repo;
    private String branch;
    private String branchName;
    private String deliveryMode;
    private List<String> mcp;
    private List<String> skills;
    private List<String> knowledge;
    private Map<String, String> env;

    @Data
    public static class Usage {
        private Integer tokensUsed;
        private Integer inputTokens;
        private Integer outputTokens;
        private Integer requestCount;
        private Long tokenLimit;
        private BigDecimal usagePercent;
        private String duration;
    }

    @Data
    public static class Report {
        private String summary;
        private Integer tokens;
        private String duration;
        private Integer completion;
        private String audit;
        private List<Artifact> artifacts;
        private String branch;
        private String commit;

        @Data
        public static class Artifact {
            private String name;
            private String url;
        }
    }
}
