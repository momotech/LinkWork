package com.linkwork.model.dto.event;

import lombok.Data;

@Data
public class SessionEventData {
    private String sessionId;
    private Long startedAt;
    private Long finishedAt;
    private Integer exitCode;
    private Integer tokensUsed;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer requestCount;
}
