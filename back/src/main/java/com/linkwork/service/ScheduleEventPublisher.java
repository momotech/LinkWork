package com.linkwork.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.model.dto.ScheduleEvent;
import com.linkwork.model.dto.event.*;
import com.linkwork.model.enums.ContainerEventType;
import com.linkwork.model.dto.event.BuildEventData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 调度事件发布服务
 * 遵循 data-format.md 规范，发布容器日志事件到 Redis Stream
 * 
 * Stream Key: stream:task:{taskId}
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScheduleEventPublisher {
    
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    
    /**
     * ISO 8601 时间戳格式化器，精确到微秒
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX")
            .withZone(ZoneOffset.UTC);
    
    /**
     * Stream Key 前缀
     */
    private static final String STREAM_KEY_PREFIX = "stream:task:";
    
    // ==================== 通用发布方法 ====================
    
    /**
     * 发布容器日志事件到 Redis Stream
     * 
     * @param taskId    任务 ID（对应 serviceId）
     * @param eventType 事件类型枚举
     * @param data      业务负载
     */
    public void publish(String taskId, ContainerEventType eventType, Object data) {
        ScheduleEvent event = ScheduleEvent.builder()
            .eventType(eventType.name())
            .timestamp(formatTimestamp(Instant.now()))
            .taskId(taskId)
            .data(data)
            .build();
        
        try {
            String eventJson = objectMapper.writeValueAsString(event);
            String streamKey = STREAM_KEY_PREFIX + taskId;
            
            // 写入 Stream（持久化，支持回溯）
            // XADD stream:task:{taskId} * payload '{...}'
            RecordId recordId = redisTemplate.opsForStream().add(
                StreamRecords.string(Map.of("payload", eventJson)).withStreamKey(streamKey)
            );
            
            log.debug("Published event to {}: type={}, recordId={}", 
                streamKey, eventType, recordId);
            
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for task {}: {}", taskId, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to publish event for task {}: {}", taskId, e.getMessage());
        }
    }
    
    // ==================== 便捷方法：调度阶段 ====================
    
    /**
     * 发布 POD_SCHEDULING 事件
     */
    public void publishPodScheduling(String taskId, String podName, int podIndex, String queueName) {
        PodSchedulingData data = PodSchedulingData.builder()
            .podName(podName)
            .podIndex(podIndex)
            .queueName(queueName)
            .build();
        publish(taskId, ContainerEventType.POD_SCHEDULING, data);
    }
    
    /**
     * 发布 POD_SCHEDULED 事件
     */
    public void publishPodScheduled(String taskId, String podName, int podIndex, String nodeName) {
        PodSchedulingData data = PodSchedulingData.builder()
            .podName(podName)
            .podIndex(podIndex)
            .nodeName(nodeName)
            .build();
        publish(taskId, ContainerEventType.POD_SCHEDULED, data);
    }
    
    // ==================== 便捷方法：镜像阶段 ====================
    
    /**
     * 发布 IMAGE_PULLING 事件
     */
    public void publishImagePulling(String taskId, String podName, String containerName, String image) {
        ImageEventData data = ImageEventData.builder()
            .podName(podName)
            .containerName(containerName)
            .image(image)
            .build();
        publish(taskId, ContainerEventType.IMAGE_PULLING, data);
    }
    
    /**
     * 发布 IMAGE_PULLED 事件
     */
    public void publishImagePulled(String taskId, String podName, String containerName, String image) {
        ImageEventData data = ImageEventData.builder()
            .podName(podName)
            .containerName(containerName)
            .image(image)
            .build();
        publish(taskId, ContainerEventType.IMAGE_PULLED, data);
    }
    
    // ==================== 便捷方法：容器阶段 ====================
    
    /**
     * 发布 CONTAINER_STARTING 事件
     */
    public void publishContainerStarting(String taskId, String podName, String containerName) {
        ContainerEventData data = ContainerEventData.builder()
            .podName(podName)
            .containerName(containerName)
            .build();
        publish(taskId, ContainerEventType.CONTAINER_STARTING, data);
    }
    
    /**
     * 发布 CONTAINER_READY 事件
     */
    public void publishContainerReady(String taskId, String podName, String containerName) {
        ContainerEventData data = ContainerEventData.builder()
            .podName(podName)
            .containerName(containerName)
            .ready(true)
            .build();
        publish(taskId, ContainerEventType.CONTAINER_READY, data);
    }
    
    // ==================== 便捷方法：环境阶段 ====================
    
    /**
     * 发布 ENV_SETUP 事件
     */
    public void publishEnvSetup(String taskId, String podName, String step, String message) {
        EnvSetupData data = EnvSetupData.builder()
            .podName(podName)
            .step(step)
            .message(message)
            .build();
        publish(taskId, ContainerEventType.ENV_SETUP, data);
    }
    
    /**
     * 发布 WORKSPACE_INIT 事件
     */
    public void publishWorkspaceInit(String taskId, String podName, String step, String message) {
        EnvSetupData data = EnvSetupData.builder()
            .podName(podName)
            .step(step)
            .message(message)
            .build();
        publish(taskId, ContainerEventType.WORKSPACE_INIT, data);
    }
    
    // ==================== 便捷方法：完成阶段 ====================
    
    /**
     * 发布 INIT_COMPLETE 事件
     */
    public void publishInitComplete(String taskId, String podName, String podGroupName, 
                                    int readyPods, int totalPods) {
        InitCompleteData data = InitCompleteData.builder()
            .podName(podName)
            .podGroupName(podGroupName)
            .readyPods(readyPods)
            .totalPods(totalPods)
            .build();
        publish(taskId, ContainerEventType.INIT_COMPLETE, data);
    }
    
    /**
     * 发布 INIT_FAILED 事件
     */
    public void publishInitFailed(String taskId, String podName, String podGroupName,
                                  String errorCode, String errorMessage) {
        InitCompleteData data = InitCompleteData.builder()
            .podName(podName)
            .podGroupName(podGroupName)
            .errorCode(errorCode)
            .errorMessage(errorMessage)
            .build();
        publish(taskId, ContainerEventType.INIT_FAILED, data);
    }
    
    // ==================== 便捷方法：生命周期事件 ====================
    
    /**
     * 发布 SESSION_START 事件
     */
    public void publishSessionStart(String taskId, String podGroupName, int podCount, 
                                    String queueName, String nodeName) {
        SessionEventData data = SessionEventData.builder()
            .podGroupName(podGroupName)
            .podCount(podCount)
            .queueName(queueName)
            .nodeName(nodeName)
            .build();
        publish(taskId, ContainerEventType.SESSION_START, data);
    }
    
    /**
     * 发布 SESSION_END 事件
     */
    public void publishSessionEnd(String taskId, String podGroupName, int podCount, boolean graceful) {
        SessionEventData data = SessionEventData.builder()
            .podGroupName(podGroupName)
            .podCount(podCount)
            .graceful(graceful)
            .build();
        publish(taskId, ContainerEventType.SESSION_END, data);
    }
    
    // ==================== 便捷方法：构建阶段 ====================
    
    /**
     * 构建事件使用独立的 Stream Key
     */
    private static final String BUILD_STREAM_KEY_PREFIX = "stream:build:";
    
    /**
     * 发布构建事件到 Redis Stream（使用 buildId 作为 key）
     */
    public void publishBuildEvent(String buildId, ContainerEventType eventType, Object data) {
        ScheduleEvent event = ScheduleEvent.builder()
            .eventType(eventType.name())
            .timestamp(formatTimestamp(Instant.now()))
            .taskId(buildId)
            .data(data)
            .build();
        
        try {
            String eventJson = objectMapper.writeValueAsString(event);
            String streamKey = BUILD_STREAM_KEY_PREFIX + buildId;
            
            RecordId recordId = redisTemplate.opsForStream().add(
                StreamRecords.string(Map.of("payload", eventJson)).withStreamKey(streamKey)
            );
            
            log.debug("Published build event to {}: type={}, recordId={}", 
                streamKey, eventType, recordId);
            
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize build event for {}: {}", buildId, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to publish build event for {}: {}", buildId, e.getMessage());
        }
    }
    
    /**
     * 发布 BUILD_STARTED 事件
     */
    public void publishBuildStarted(String buildId, String buildNo, Long roleId, 
                                    String roleName, String baseImage) {
        BuildEventData data = BuildEventData.builder()
            .buildNo(buildNo)
            .roleId(roleId)
            .roleName(roleName)
            .baseImage(baseImage)
            .build();
        publishBuildEvent(buildId, ContainerEventType.BUILD_STARTED, data);
    }
    
    /**
     * 发布 BUILD_PROGRESS 事件
     */
    public void publishBuildProgress(String buildId, String buildNo, String step, String message) {
        BuildEventData data = BuildEventData.builder()
            .buildNo(buildNo)
            .step(step)
            .message(message)
            .build();
        publishBuildEvent(buildId, ContainerEventType.BUILD_PROGRESS, data);
    }
    
    /**
     * 发布 BUILD_LOG 事件（Docker 实时日志行）
     */
    public void publishBuildLog(String buildId, String buildNo, String logLine, String logLevel) {
        BuildEventData data = BuildEventData.builder()
            .buildNo(buildNo)
            .message(logLine)
            .step(logLevel)  // 使用 step 字段存储日志级别 (info, warn, error, debug)
            .build();
        publishBuildEvent(buildId, ContainerEventType.BUILD_LOG, data);
    }
    
    /**
     * 发布 BUILD_COMPLETED 事件
     */
    public void publishBuildCompleted(String buildId, String buildNo, String imageTag, Long durationMs) {
        BuildEventData data = BuildEventData.builder()
            .buildNo(buildNo)
            .imageTag(imageTag)
            .durationMs(durationMs)
            .build();
        publishBuildEvent(buildId, ContainerEventType.BUILD_COMPLETED, data);
    }
    
    /**
     * 发布 BUILD_FAILED 事件
     */
    public void publishBuildFailed(String buildId, String buildNo, String errorCode, 
                                   String errorMessage, Long durationMs) {
        BuildEventData data = BuildEventData.builder()
            .buildNo(buildNo)
            .errorCode(errorCode)
            .errorMessage(errorMessage)
            .durationMs(durationMs)
            .build();
        publishBuildEvent(buildId, ContainerEventType.BUILD_FAILED, data);
    }
    
    /**
     * 发布 BUILD_PUSHING 事件
     */
    public void publishBuildPushing(String buildId, String buildNo, String imageTag) {
        BuildEventData data = BuildEventData.builder()
            .buildNo(buildNo)
            .imageTag(imageTag)
            .message("Pushing image to registry...")
            .build();
        publishBuildEvent(buildId, ContainerEventType.BUILD_PUSHING, data);
    }
    
    /**
     * 发布 BUILD_PUSHED 事件
     */
    public void publishBuildPushed(String buildId, String buildNo, String imageTag) {
        BuildEventData data = BuildEventData.builder()
            .buildNo(buildNo)
            .imageTag(imageTag)
            .message("Image pushed successfully")
            .build();
        publishBuildEvent(buildId, ContainerEventType.BUILD_PUSHED, data);
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 格式化时间戳为 ISO 8601 格式，精确到微秒
     */
    private String formatTimestamp(Instant instant) {
        return TIMESTAMP_FORMATTER.format(instant);
    }
}
