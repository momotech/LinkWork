package com.linkwork.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 报表可导出字段响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportExportFieldResponse {

    /**
     * 导出类型：task / role
     */
    private String type;

    /**
     * 字段定义列表
     */
    private List<ReportExportFieldOption> fields;
}
