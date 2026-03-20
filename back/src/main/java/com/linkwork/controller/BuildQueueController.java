package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.model.dto.BuildQueueStatus;
import com.linkwork.service.BuildQueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 构建队列控制器
 * 
 * 提供队列状态查询和任务管理 API
 */
@RestController
@RequestMapping("/api/v1/build-queue")
@Slf4j
public class BuildQueueController {
    
    private final BuildQueueService buildQueueService;
    
    public BuildQueueController(BuildQueueService buildQueueService) {
        this.buildQueueService = buildQueueService;
    }
    
    /**
     * 获取队列状态
     * 
     * 返回：等待任务数、执行中任务数、系统资源状态等
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<BuildQueueStatus>> getStatus() {
        if (!buildQueueService.isEnabled()) {
            return ResponseEntity.ok(ApiResponse.error("Build queue is disabled"));
        }
        
        BuildQueueStatus status = buildQueueService.getStatus();
        return ResponseEntity.ok(ApiResponse.success(status));
    }
    
    /**
     * 获取任务在队列中的位置
     * 
     * @param buildId 构建 ID
     * @return 位置信息：正数表示排队位置，-1 表示正在执行，null 表示不存在
     */
    @GetMapping("/position/{buildId}")
    public ResponseEntity<ApiResponse<PositionResponse>> getPosition(@PathVariable String buildId) {
        if (!buildQueueService.isEnabled()) {
            return ResponseEntity.ok(ApiResponse.error("Build queue is disabled"));
        }
        
        Integer position = buildQueueService.getPosition(buildId);
        
        if (position == null) {
            return ResponseEntity.ok(ApiResponse.error("Task not found in queue"));
        }
        
        String status;
        String message;
        if (position == -1) {
            status = "RUNNING";
            message = "Task is currently executing";
        } else {
            status = "WAITING";
            message = "Task is waiting at position " + position;
        }
        
        PositionResponse response = new PositionResponse(buildId, position, status, message);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    /**
     * 取消排队中的任务
     * 
     * 注意：只能取消等待中的任务，正在执行的任务无法取消
     * 
     * @param buildId 构建 ID
     */
    @DeleteMapping("/{buildId}")
    public ResponseEntity<ApiResponse<Void>> cancel(@PathVariable String buildId) {
        if (!buildQueueService.isEnabled()) {
            return ResponseEntity.ok(ApiResponse.error("Build queue is disabled"));
        }
        
        log.info("收到取消任务请求: buildId={}", buildId);
        
        boolean cancelled = buildQueueService.cancel(buildId);
        
        if (cancelled) {
            return ResponseEntity.ok(ApiResponse.success(null));
        } else {
            return ResponseEntity.ok(ApiResponse.error("Cannot cancel task: either running or not found"));
        }
    }
    
    /**
     * 位置响应 DTO
     */
    public record PositionResponse(
        String buildId,
        int position,
        String status,
        String message
    ) {}
}
