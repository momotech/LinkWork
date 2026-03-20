package com.linkwork.model.dto;

import com.linkwork.model.enums.ResumeType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 服务恢复结果（Resume 服务输出）
 */
@Data
@Builder
public class ServiceResumeResult {
    
    private String serviceId;
    private boolean success;
    
    // 恢复类型
    private ResumeType resumeType;         // WARM / COLD
    
    // 如果快照不存在或已过期，需要完整请求重新构建
    private boolean requireFullRequest;
    
    // 成功时的信息
    private String podGroupName;
    private List<String> podNames;
    private String scheduledNode;          // 实际调度节点
    private boolean imageCached;           // 是否命中镜像缓存
    private Integer estimatedReadySeconds; // 预计就绪时间（秒）
    
    // 失败时的信息
    private String errorCode;
    private String message;
    
    /**
     * 快照不存在或已过期
     */
    public static ServiceResumeResult snapshotNotFound(String serviceId) {
        return ServiceResumeResult.builder()
            .serviceId(serviceId)
            .success(false)
            .resumeType(ResumeType.COLD)
            .requireFullRequest(true)
            .errorCode("SNAPSHOT_NOT_FOUND")
            .message("快照不存在或已过期，需要完整的 Build 请求")
            .build();
    }
    
    /**
     * 恢复成功
     */
    public static ServiceResumeResult success(String serviceId, String podGroupName, 
                                               List<String> podNames, String scheduledNode, 
                                               boolean imageCached) {
        return ServiceResumeResult.builder()
            .serviceId(serviceId)
            .success(true)
            .resumeType(imageCached ? ResumeType.WARM : ResumeType.COLD)
            .podGroupName(podGroupName)
            .podNames(podNames)
            .scheduledNode(scheduledNode)
            .imageCached(imageCached)
            .estimatedReadySeconds(imageCached ? 20 : 90)
            .build();
    }
    
    /**
     * 恢复失败
     */
    public static ServiceResumeResult failed(String serviceId, String errorCode, String message) {
        return ServiceResumeResult.builder()
            .serviceId(serviceId)
            .success(false)
            .errorCode(errorCode)
            .message(message)
            .build();
    }
}
