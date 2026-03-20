package com.linkwork.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 报表字段元数据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportExportFieldOption {

    /**
     * Java 字段名
     */
    private String field;

    /**
     * 数据库列名
     */
    private String column;

    /**
     * 前端展示名
     */
    private String label;

    /**
     * Java 类型名
     */
    private String javaType;
}
