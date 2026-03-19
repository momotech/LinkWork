package com.linkwork.model.dto.event;

import lombok.Data;

@Data
public class PodSchedulingData {
    private String podName;
    private String namespace;
    private String nodeName;
    private String message;
}
