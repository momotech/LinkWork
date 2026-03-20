package com.linkwork.service;

import com.linkwork.model.dto.*;

import java.util.List;

/**
 * K8s 编排器接口
 */
public interface K8sOrchestrator {
    
    /**
     * 构建服务（核心方法）
     * 执行顺序：Secret → PodGroup → Pod ×N
     * @param config 融合后的配置
     * @return 构建结果
     */
    ServiceBuildResult buildService(MergedConfig config);
    
    /**
     * 获取服务状态
     * @param serviceId 服务 ID
     */
    ServiceStatusResponse getServiceStatus(String serviceId);
    
    /**
     * 停止服务
     * @param serviceId 服务 ID
     * @param graceful true=优雅停止
     */
    StopResult stopService(String serviceId, boolean graceful);
    
    /**
     * 清理服务资源
     * @param serviceId 服务 ID
     */
    void cleanupService(String serviceId);
    
    /**
     * 预览生成的 Spec（不实际创建）
     * @param config 融合后的配置
     * @return 生成的 Spec
     */
    GeneratedSpec previewSpec(MergedConfig config);
    
    // ==================== 动态伸缩接口 ====================
    
    /**
     * 缩容：删除指定的 Pod
     * @param serviceId 服务 ID
     * @param podName 要删除的 Pod 名称（必填，不指定则返回错误）
     * @return 伸缩结果
     */
    ScaleResult scaleDown(String serviceId, String podName);
    
    /**
     * 扩容：创建新的 Pod 到指定数量
     * @param serviceId 服务 ID
     * @param targetPodCount 目标 Pod 数量
     * @param config 融合后的配置（用于创建新 Pod）
     * @return 伸缩结果
     */
    ScaleResult scaleUp(String serviceId, int targetPodCount, MergedConfig config);
    
    /**
     * 获取当前运行的 Pod 列表
     * @param serviceId 服务 ID
     * @return Pod 名称列表
     */
    List<String> getRunningPods(String serviceId);

    /**
     * 扫描 K8s namespace，获取所有带 service-id label 的运行中服务 ID 列表
     * 用于后端重启后反向发现 K8s 中仍在运行的服务
     * @return 去重后的 serviceId 列表
     */
    List<String> listAllServiceIds();
}
