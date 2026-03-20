package com.linkwork.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 报表导出请求
 */
@Data
public class ReportExportRequest {

    /**
     * 导出类型：task / role
     */
    @NotBlank(message = "导出类型不能为空")
    private String type;

    /**
     * 开始时间，支持 yyyy-MM-dd'T'HH:mm 或 yyyy-MM-dd'T'HH:mm:ss
     */
    @NotBlank(message = "开始时间不能为空")
    private String startTime;

    /**
     * 结束时间，支持 yyyy-MM-dd'T'HH:mm 或 yyyy-MM-dd'T'HH:mm:ss
     */
    @NotBlank(message = "结束时间不能为空")
    private String endTime;

    /**
     * 导出字段（为空时导出全部）
     */
    private List<String> fields;

    /**
     * 是否附带消息流（仅 task 生效）
     */
    private Boolean includeEventStream;
}
