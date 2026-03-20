package com.linkwork.service;

import com.linkwork.config.ImageBuildConfig;
import com.linkwork.model.dto.BuildTask;
import com.linkwork.model.dto.ImageBuildResult;
import com.linkwork.model.dto.MergedConfig;
import com.linkwork.model.dto.ServiceBuildRequest;
import com.linkwork.model.dto.ServiceBuildResult;
import com.linkwork.model.enums.DeployMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 构建执行器
 * 
 * 封装实际的构建逻辑，由 BuildQueueService 调用
 */
@Component
@Slf4j
public class BuildExecutor {
    
    private final ImageBuildService imageBuildService;
    private final ImageBuildConfig imageBuildConfig;
    private final K8sOrchestrator orchestrator;
    private final BuildRecordService buildRecordService;
    private final ScheduleEventPublisher eventPublisher;
    private final BuildLogBuffer buildLogBuffer;
    
    public BuildExecutor(ImageBuildService imageBuildService,
                        ImageBuildConfig imageBuildConfig,
                        K8sOrchestrator orchestrator,
                        BuildRecordService buildRecordService,
                        ScheduleEventPublisher eventPublisher,
                        BuildLogBuffer buildLogBuffer) {
        this.imageBuildService = imageBuildService;
        this.imageBuildConfig = imageBuildConfig;
        this.orchestrator = orchestrator;
        this.buildRecordService = buildRecordService;
        this.eventPublisher = eventPublisher;
        this.buildLogBuffer = buildLogBuffer;
    }
    
    /**
     * 执行构建任务
     * 
     * @param task 构建任务
     * @return 构建结果
     */
    public ServiceBuildResult execute(BuildTask task) {
        ServiceBuildRequest request = task.getRequest();
        MergedConfig config = task.getConfig();
        String serviceId = request.getServiceId();
        String buildId = request.getBuildId();
        Long roleId = request.getRoleId();
        String roleName = request.getRoleName();
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("开始执行构建: serviceId={}, buildId={}", serviceId, buildId);
            addBuildLog(buildId, "INFO", "========== 开始构建 ==========");
            addBuildLog(buildId, "INFO", "服务ID: " + serviceId);
            addBuildLog(buildId, "INFO", "构建ID: " + buildId);
            
            // 更新构建记录状态为 BUILDING
            if (roleId != null && StringUtils.hasText(buildId)) {
                buildRecordService.markBuilding(buildId);
            }
            
            // 1. 镜像构建（如果启用，仅构建 Agent 镜像）
            if (imageBuildConfig.isEnabled() && request.getDeployMode() == DeployMode.K8S) {
                log.info("镜像构建已启用，开始构建: serviceId={}", serviceId);
                addBuildLog(buildId, "INFO", "========== 镜像构建阶段 ==========");
                
                // 发布 BUILD_STARTED 事件
                if (StringUtils.hasText(buildId)) {
                    eventPublisher.publishBuildStarted(buildId, buildId, roleId, roleName, 
                        config.getAgentImage());
                    eventPublisher.publishBuildProgress(buildId, buildId, "dockerfile", 
                        "Generating Dockerfile...");
                }
                
                ImageBuildResult buildResult = imageBuildService.buildImages(request);
                
                if (!buildResult.isSuccess()) {
                    log.error("镜像构建失败: serviceId={}, error={}", serviceId, buildResult.getErrorMessage());
                    addBuildLog(buildId, "ERROR", "镜像构建失败: " + buildResult.getErrorMessage());
                    
                    // 发布 BUILD_FAILED 事件并更新记录
                    long durationMs = System.currentTimeMillis() - startTime;
                    finishBuild(buildId, false, durationMs);
                    if (StringUtils.hasText(buildId)) {
                        eventPublisher.publishBuildFailed(buildId, buildId, "BUILD_ERROR", 
                            buildResult.getErrorMessage(), durationMs);
                        if (roleId != null) {
                            buildRecordService.markFailed(buildId, buildResult.getErrorMessage(), durationMs);
                        }
                    }
                    return ServiceBuildResult.failed(serviceId, "BUILD_ERROR", buildResult.getErrorMessage());
                }
                
                // 只更新 Agent 镜像地址
                config.setAgentImage(buildResult.getAgentImageTag());
                config.setImageBuildDurationMs(buildResult.getBuildDurationMs());
                
                // 发布镜像推送事件（如果已推送）
                if (buildResult.isPushed() && StringUtils.hasText(buildId)) {
                    eventPublisher.publishBuildPushed(buildId, buildId, buildResult.getAgentImageTag());
                }
                
                log.info("镜像构建完成: serviceId={}, agentImage={}, duration={}ms", 
                    serviceId, buildResult.getAgentImageTag(), buildResult.getBuildDurationMs());
                addBuildLog(buildId, "INFO", "镜像构建完成: " + buildResult.getAgentImageTag());
            } else {
                log.info("镜像构建已禁用或非 K8s 模式，使用基础镜像: serviceId={}", serviceId);
                addBuildLog(buildId, "INFO", "使用基础镜像: " + config.getAgentImage());
            }
            
            // 2. 创建 K8s 资源
            addBuildLog(buildId, "INFO", "========== K8s 资源创建阶段 ==========");
            addBuildLog(buildId, "INFO", "命名空间: " + config.getNamespace());
            addBuildLog(buildId, "INFO", "Pod 数量: " + config.getPodCount());
            addBuildLog(buildId, "INFO", "Pod 模式: " + config.getPodMode());
            
            ServiceBuildResult result = orchestrator.buildService(config);
            
            long durationMs = System.currentTimeMillis() - startTime;
            
            if (result.isSuccess()) {
                log.info("构建完成: serviceId={}, duration={}ms", serviceId, durationMs);
                addBuildLog(buildId, "INFO", "========== 构建成功 ==========");
                addBuildLog(buildId, "INFO", "PodGroup: " + result.getPodGroupName());
                addBuildLog(buildId, "INFO", "Pods: " + result.getPodNames());
                addBuildLog(buildId, "INFO", "调度节点: " + result.getScheduledNode());
                addBuildLog(buildId, "INFO", "总耗时: " + durationMs + "ms");
                finishBuild(buildId, true, durationMs);
                
                // 发布 BUILD_COMPLETED 事件并更新记录
                if (StringUtils.hasText(buildId)) {
                    eventPublisher.publishBuildCompleted(buildId, buildId, config.getAgentImage(), durationMs);
                    if (roleId != null) {
                        buildRecordService.markSuccess(buildId, config.getAgentImage(), durationMs);
                    }
                }
            } else {
                log.error("K8s 资源创建失败: serviceId={}, error={}", serviceId, result.getErrorMessage());
                addBuildLog(buildId, "ERROR", "========== K8s 资源创建失败 ==========");
                addBuildLog(buildId, "ERROR", "错误码: " + result.getErrorCode());
                addBuildLog(buildId, "ERROR", "错误信息: " + result.getErrorMessage());
                addBuildLog(buildId, "INFO", "总耗时: " + durationMs + "ms");
                finishBuild(buildId, false, durationMs);
                
                // 发布 BUILD_FAILED 事件并更新记录
                if (StringUtils.hasText(buildId)) {
                    eventPublisher.publishBuildFailed(buildId, buildId, "K8S_ERROR", 
                        result.getErrorMessage(), durationMs);
                    if (roleId != null) {
                        buildRecordService.markFailed(buildId, result.getErrorMessage(), durationMs);
                    }
                }
            }
            
            return result;
            
        } catch (Throwable t) {
            log.error("构建执行异常: serviceId={}, error={}", serviceId, t.getMessage(), t);
            
            long durationMs = System.currentTimeMillis() - startTime;
            
            addBuildLog(buildId, "ERROR", "========== 构建执行异常 ==========");
            addBuildLog(buildId, "ERROR", "异常类型: " + t.getClass().getSimpleName());
            addBuildLog(buildId, "ERROR", "异常信息: " + t.getMessage());
            addBuildLog(buildId, "INFO", "总耗时: " + durationMs + "ms");
            finishBuild(buildId, false, durationMs);
            
            // 发布 BUILD_FAILED 事件并更新记录
            if (StringUtils.hasText(buildId)) {
                eventPublisher.publishBuildFailed(buildId, buildId, "INTERNAL_ERROR", 
                    t.getMessage(), durationMs);
                if (roleId != null) {
                    buildRecordService.markFailed(buildId, t.getMessage(), durationMs);
                }
            }
            
            return ServiceBuildResult.failed(serviceId, "INTERNAL_ERROR", t.getMessage());
        }
    }
    
    /**
     * 添加构建日志
     */
    private void addBuildLog(String buildId, String level, String message) {
        if (StringUtils.hasText(buildId)) {
            buildLogBuffer.addLog(buildId, level, message);
        }
    }
    
    /**
     * 完成构建（标记完成并调度清理）
     */
    private void finishBuild(String buildId, boolean success, long durationMs) {
        if (StringUtils.hasText(buildId)) {
            // 先标记完成状态
            buildLogBuffer.markCompleted(buildId, success);
            // 添加最终状态日志（触发订阅者发送 complete 事件）
            addBuildLog(buildId, "SYSTEM", success ? "构建完成" : "构建失败");
            // 延迟 30 分钟清理日志缓冲区
            buildLogBuffer.scheduleCleanup(buildId, 30);
        }
    }
}
