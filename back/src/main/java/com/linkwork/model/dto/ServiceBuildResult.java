package com.linkwork.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 服务构建结果（Build 服务输出）
 * 
 * 包含镜像构建结果和 K8s 资源创建结果
 */
@Data
@Builder
public class ServiceBuildResult {
    private String serviceId;
    private boolean success;
    private String status;              // 状态：SUCCESS, FAILED, BUILDING
    private String podGroupName;        // 创建的 PodGroup 名称（仅 K8s 模式）
    private List<String> podNames;      // 创建的 Pod 名称列表（仅 K8s 模式，BUILDING 时为空）
    private String queueName;           // 分配的队列名称（仅 K8s 模式）
    private String createdAt;
    
    // ========== 构建追踪 ==========
    /**
     * 构建 ID（用于订阅日志和查询状态）
     */
    private String buildId;
    
    /**
     * 提示信息（BUILDING 状态时的说明）
     */
    private String message;
    
    // ========== 镜像构建结果 ==========
    /**
     * 构建的 Agent 镜像地址
     * 格式：{registry}/service-{serviceId}-agent:{timestamp}
     * BUILDING 状态时为 null
     */
    private String builtAgentImage;
    
    /**
     * 镜像构建耗时（毫秒）
     * BUILDING 状态时为 null
     */
    private Long buildDurationMs;
    
    // ========== Compose 模式返回 ==========
    /**
     * docker-compose.yaml 内容（仅 Compose 模式，已废弃）
     * @deprecated 使用 composeArchive 代替
     */
    @Deprecated
    private String composeYaml;

    /**
     * Compose 构建包 tar.gz 字节（仅 Compose 模式）
     * 包含 docker-compose.yaml、Dockerfile、build.sh、config.json、start.sh、README.md 等
     */
    private byte[] composeTar;
    
    // 调度信息（用于快速恢复）
    private String scheduledNode;       // 实际调度到的节点，用于保存到 Snapshot（BUILDING 时为 null）
    
    // 失败时
    private String errorCode;
    private String errorMessage;
    
    /**
     * K8s 模式构建成功
     */
    public static ServiceBuildResult successK8s(String serviceId, String podGroupName, 
                                                 List<String> podNames, String queueName,
                                                 String builtAgentImage, Long buildDurationMs) {
        return ServiceBuildResult.builder()
            .serviceId(serviceId)
            .success(true)
            .status("SUCCESS")
            .podGroupName(podGroupName)
            .podNames(podNames)
            .queueName(queueName)
            .builtAgentImage(builtAgentImage)
            .buildDurationMs(buildDurationMs)
            .createdAt(java.time.Instant.now().toString())
            .build();
    }
    
    /**
     * K8s 模式构建成功（带调度节点）
     */
    public static ServiceBuildResult successK8s(String serviceId, String podGroupName, 
                                                 List<String> podNames, String queueName,
                                                 String scheduledNode,
                                                 String builtAgentImage, Long buildDurationMs) {
        return ServiceBuildResult.builder()
            .serviceId(serviceId)
            .success(true)
            .status("SUCCESS")
            .podGroupName(podGroupName)
            .podNames(podNames)
            .queueName(queueName)
            .scheduledNode(scheduledNode)
            .builtAgentImage(builtAgentImage)
            .buildDurationMs(buildDurationMs)
            .createdAt(java.time.Instant.now().toString())
            .build();
    }
    
    /**
     * Compose 模式构建成功（返回 tar.gz 构建包）
     */
    public static ServiceBuildResult successCompose(String serviceId, byte[] composeTar) {
        return ServiceBuildResult.builder()
            .serviceId(serviceId)
            .success(true)
            .status("SUCCESS")
            .composeTar(composeTar)
            .createdAt(java.time.Instant.now().toString())
            .build();
    }
    
    /**
     * 兼容旧版本：K8s 模式构建成功（不含镜像信息）
     * @deprecated 使用 successK8s 方法
     */
    @Deprecated
    public static ServiceBuildResult success(String serviceId, String podGroupName, 
                                              List<String> podNames, String queueName) {
        return ServiceBuildResult.builder()
            .serviceId(serviceId)
            .success(true)
            .podGroupName(podGroupName)
            .podNames(podNames)
            .queueName(queueName)
            .createdAt(java.time.Instant.now().toString())
            .build();
    }
    
    /**
     * 兼容旧版本：K8s 模式构建成功（带调度节点，不含镜像信息）
     * @deprecated 使用 successK8s 方法
     */
    @Deprecated
    public static ServiceBuildResult success(String serviceId, String podGroupName, 
                                              List<String> podNames, String queueName,
                                              String scheduledNode) {
        return ServiceBuildResult.builder()
            .serviceId(serviceId)
            .success(true)
            .podGroupName(podGroupName)
            .podNames(podNames)
            .queueName(queueName)
            .scheduledNode(scheduledNode)
            .createdAt(java.time.Instant.now().toString())
            .build();
    }
    
    public static ServiceBuildResult failed(String serviceId, String errorCode, String errorMessage) {
        return ServiceBuildResult.builder()
            .serviceId(serviceId)
            .success(false)
            .status("FAILED")
            .errorCode(errorCode)
            .errorMessage(errorMessage)
            .createdAt(java.time.Instant.now().toString())
            .build();
    }
    
    /**
     * 构建中（任务已提交，异步执行中）
     * 
     * @param serviceId 服务 ID
     * @param buildId 构建 ID（用于订阅日志）
     * @param podGroupName PodGroup 名称
     * @param queueName 队列名称
     * @param message 提示信息
     */
    public static ServiceBuildResult building(String serviceId, String buildId, 
                                               String podGroupName, String queueName, String message) {
        return ServiceBuildResult.builder()
            .serviceId(serviceId)
            .success(true)
            .status("BUILDING")
            .buildId(buildId)
            .podGroupName(podGroupName)
            .queueName(queueName)
            .message(message)
            .createdAt(java.time.Instant.now().toString())
            .build();
    }
}
