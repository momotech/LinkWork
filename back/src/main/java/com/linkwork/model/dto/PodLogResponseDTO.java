package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PodLogResponseDTO {
    private String podName;
    private String namespace;
    private String containerName;
    private String logs;
    private int tailLines;
}
