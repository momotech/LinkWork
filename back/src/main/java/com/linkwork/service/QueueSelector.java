package com.linkwork.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 队列选择器
 */
@Service
@Slf4j
public class QueueSelector {

    @Value("${schedule.queue.prefix:ai-worker}")
    private String queuePrefix;
    
    /**
     * 根据优先级选择调度队列
     * @param priority 优先级 0-100
     * @param gpuRequired 是否需要 GPU（K8s 不支持 GPU）
     */
    public String selectQueue(Integer priority, Boolean gpuRequired) {
        int p = Optional.ofNullable(priority).orElse(50);
        
        if (Boolean.TRUE.equals(gpuRequired)) {
            log.warn("GPU tasks should use Compose mode, K8s does not support GPU");
        }
        
        if (p >= 90) return queuePrefix + "-critical";
        if (p >= 70) return queuePrefix + "-high";
        if (p >= 30) return queuePrefix + "-normal";
        return queuePrefix + "-low";
    }
}
