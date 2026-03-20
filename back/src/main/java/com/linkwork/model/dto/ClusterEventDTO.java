package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ClusterEventDTO {
    private String type;
    private String reason;
    private String message;
    private String objectKind;
    private String objectName;
    private String namespace;
    private String firstTimestamp;
    private String lastTimestamp;
    private Integer count;
}
