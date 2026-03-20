package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 任务完成回写请求 DTO
 * 
 * Worker 在任务执行完毕后，通过此接口回写执行结果。
 * 调用方：momo-worker (Workstation 执行器)
 */
@Data
public class TaskCompleteRequest {

    /**
     * 最终任务状态
     * 允许值：COMPLETED, FAILED
     */
    @NotBlank(message = "任务状态不能为空")
    private String status;

    /**
     * 消耗的 Token 总数
     */
    @NotNull(message = "Token 消耗不能为空")
    private Integer tokensUsed;

    /**
     * 任务执行时长（毫秒）
     */
    @NotNull(message = "执行时长不能为空")
    private Long durationMs;

    /**
     * 任务报告（可选）
     */
    private Report report;

    @Data
    public static class Report {
        /**
         * 执行摘要
         */
        private String summary;

        /**
         * 完成度百分比 (0-100)
         */
        private Integer completion;

        /**
         * 审计评级 (A/B/C/D)
         */
        private String audit;

        /**
         * 产出物列表
         */
        private List<Artifact> artifacts;

        /**
         * Git 分支名
         */
        private String branch;

        /**
         * Git 提交哈希
         */
        private String commit;
    }

    @Data
    public static class Artifact {
        private String name;
        private String url;
    }
}
