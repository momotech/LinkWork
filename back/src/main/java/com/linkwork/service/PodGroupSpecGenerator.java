package com.linkwork.service;

import com.linkwork.model.dto.MergedConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PodGroup Spec 生成器
 */
@Component("legacyPodGroupSpecGenerator")
@Slf4j
public class PodGroupSpecGenerator {
    
    /**
     * 生成 PodGroup Spec（显式创建）
     */
    public Map<String, Object> generate(MergedConfig config) {
        String podGroupName = "svc-" + config.getServiceId() + "-pg";
        
        log.info("Generating PodGroup spec: {}, minMember: {}", 
            podGroupName, config.getPodCount());
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiVersion", "scheduling.volcano.sh/v1beta1");
        result.put("kind", "PodGroup");
        
        // metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", podGroupName);
        metadata.put("namespace", config.getNamespace());
        metadata.put("labels", Map.of(
            "app", "ai-worker-service",
            "service-id", config.getServiceId()
        ));
        result.put("metadata", metadata);
        
        // spec
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("minMember", config.getPodCount());
        spec.put("queue", config.getQueueName());
        spec.put("priorityClassName", config.getPriorityClassName());
        spec.put("minResources", buildMinResources(config));
        result.put("spec", spec);
        
        return result;
    }
    
    /**
     * 计算总资源需求
     */
    private Map<String, String> buildMinResources(MergedConfig config) {
        Map<String, String> resources = new HashMap<>();
        
        // 计算总 CPU（Pod 数量 × 单 Pod CPU）
        double totalCpu = parseCpu(config.getAgentResources().getCpuRequest()) * config.getPodCount();
        resources.put("cpu", String.valueOf((int) Math.ceil(totalCpu)));
        
        // 计算总内存
        long totalMemory = parseMemory(config.getAgentResources().getMemoryRequest()) * config.getPodCount();
        resources.put("memory", formatMemory(totalMemory));
        
        return resources;
    }
    
    private double parseCpu(String cpu) {
        if (cpu == null) return 1.0;
        if (cpu.endsWith("m")) {
            return Double.parseDouble(cpu.replace("m", "")) / 1000;
        }
        return Double.parseDouble(cpu);
    }
    
    private long parseMemory(String memory) {
        if (memory == null) return 2L * 1024 * 1024 * 1024; // 默认 2Gi
        if (memory.endsWith("Gi")) {
            return Long.parseLong(memory.replace("Gi", "")) * 1024 * 1024 * 1024;
        }
        if (memory.endsWith("Mi")) {
            return Long.parseLong(memory.replace("Mi", "")) * 1024 * 1024;
        }
        return Long.parseLong(memory);
    }
    
    private String formatMemory(long bytes) {
        return (bytes / (1024 * 1024 * 1024)) + "Gi";
    }
}
