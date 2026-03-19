package com.linkwork.model.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TaskCompleteRequest {
    private String status;
    private Integer tokensUsed;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer requestCount;
    private BigDecimal usagePercent;
    private Long durationMs;
    private Object reportJson;
}
