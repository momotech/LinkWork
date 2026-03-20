package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 生成的 Spec 结果（用于预览）
 */
@Data
@Builder
public class GeneratedSpec {
    private String serviceId;
    private Map<String, Object> podGroupSpec;    // PodGroup YAML
    private List<Map<String, Object>> podSpecs;  // Pod YAML 列表
    private String composeYaml;                   // Docker Compose YAML (如果是 COMPOSE 模式)
}
