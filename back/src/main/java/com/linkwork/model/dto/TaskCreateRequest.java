package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 创建任务请求 DTO
 * 
 * 简化版本：前端只需传递核心参数，其他配置由后端根据岗位自动填充
 */
@Data
public class TaskCreateRequest {

    /**
     * 用户输入的任务指令
     */
    @NotBlank(message = "任务指令不能为空")
    private String prompt;

    /**
     * 执行岗位 ID
     */
    @NotNull(message = "岗位 ID 不能为空")
    private Long roleId;

    /**
     * 选择的模型 ID（如 claude-opus-4-5, deepseek-v3 等）
     */
    @NotBlank(message = "模型 ID 不能为空")
    private String modelId;

    /**
     * 用户上传的文档文件 ID 列表（OSS 文件 key）
     */
    private List<String> fileIds;
}
