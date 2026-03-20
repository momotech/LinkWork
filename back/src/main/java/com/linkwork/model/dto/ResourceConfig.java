package com.linkwork.model.dto;

import lombok.Data;

/**
 * 资源配置
 */
@Data
public class ResourceConfig {
    private String cpuRequest;          // "1" / "500m"
    private String cpuLimit;
    private String memoryRequest;       // "2Gi" / "512Mi"
    private String memoryLimit;
}
