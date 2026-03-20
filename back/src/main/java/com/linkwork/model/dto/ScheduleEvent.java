package com.linkwork.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一信封格式
 * 遵循 data-format.md 规范定义
 * 
 * 所有写入 Redis Stream 的消息共享统一的外层结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleEvent {
    
    /**
     * 事件类型枚举，大写下划线分隔
     * 例如：POD_SCHEDULING, POD_SCHEDULED, IMAGE_PULLING 等
     */
    @JsonProperty("event_type")
    private String eventType;
    
    /**
     * ISO 8601 带时区，精确到微秒
     * 格式：2026-01-29T10:00:00.000000+00:00
     */
    private String timestamp;
    
    /**
     * 任务标识（对应 serviceId）
     */
    @JsonProperty("task_id")
    private String taskId;
    
    /**
     * 业务负载，结构由 event_type 决定
     */
    private Object data;
}
