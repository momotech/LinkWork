package com.linkwork.service;

import com.linkwork.model.dto.ServiceBuildRequest;
import com.linkwork.model.dto.ServiceBuildResult;
import com.linkwork.model.dto.ServiceResumeResult;
import com.linkwork.model.dto.ServiceSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 服务快速恢复服务
 * 
 * 利用镜像缓存 + 节点亲和调度实现快速重启：
 * 1. 查询服务快照
 * 2. 还原原始请求
 * 3. 设置 preferredNode（优先调度到原节点）
 * 4. 调用 Build 服务
 * 
 * 效果：命中镜像缓存时，启动时间从 ~90s 降到 ~20s
 */
@Service
@Slf4j
public class ServiceResumeService {
    
    private final ServiceSnapshotService snapshotService;
    private final ServiceScheduleService scheduleService;
    
    public ServiceResumeService(ServiceSnapshotService snapshotService,
                                ServiceScheduleService scheduleService) {
        this.snapshotService = snapshotService;
        this.scheduleService = scheduleService;
    }
    
    /**
     * 快速恢复 Service
     * 
     * @param serviceId 服务 ID
     * @return 恢复结果
     */
    public ServiceResumeResult resume(String serviceId) {
        log.info("Resuming service {}", serviceId);
        
        // 1. 查询快照
        Optional<ServiceSnapshot> snapshotOpt = snapshotService.findActiveSnapshot(serviceId);
        
        if (snapshotOpt.isEmpty()) {
            log.info("No active snapshot for service {}, requires full build request", serviceId);
            return ServiceResumeResult.snapshotNotFound(serviceId);
        }
        
        ServiceSnapshot snapshot = snapshotOpt.get();
        String lastScheduledNode = snapshot.getLastScheduledNode();
        
        // 2. 还原原始请求
        ServiceBuildRequest request = snapshotService.restoreRequest(snapshot);
        if (request == null) {
            return ServiceResumeResult.failed(serviceId, "RESTORE_FAILED", 
                "无法还原原始请求");
        }
        
        // 3. 设置优先调度节点（关键！用于命中镜像缓存）
        request.setPreferredNode(lastScheduledNode);
        log.info("Resume service {}: setting preferredNode={}", serviceId, lastScheduledNode);
        
        // 4. 调用 Build 服务
        try {
            ServiceBuildResult buildResult = scheduleService.build(request);
            
            if (!buildResult.isSuccess()) {
                return ServiceResumeResult.failed(serviceId, 
                    buildResult.getErrorCode(), buildResult.getErrorMessage());
            }
            
            // 5. 更新快照
            snapshotService.onServiceResumed(serviceId, buildResult.getScheduledNode());
            
            // 6. 判断是否命中缓存
            boolean imageCached = lastScheduledNode != null 
                && lastScheduledNode.equals(buildResult.getScheduledNode());
            
            log.info("Service {} resumed successfully, scheduledNode={}, imageCached={}", 
                serviceId, buildResult.getScheduledNode(), imageCached);
            
            return ServiceResumeResult.success(
                serviceId,
                buildResult.getPodGroupName(),
                buildResult.getPodNames(),
                buildResult.getScheduledNode(),
                imageCached
            );
            
        } catch (Exception e) {
            log.error("Failed to resume service {}: {}", serviceId, e.getMessage(), e);
            return ServiceResumeResult.failed(serviceId, "RESUME_FAILED", 
                "恢复失败: " + e.getMessage());
        }
    }
}
