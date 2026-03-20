package com.linkwork.service;

import com.linkwork.config.EnvConfig;
import com.linkwork.config.ImageBuildConfig;
import com.linkwork.model.dto.MergedConfig;
import com.linkwork.model.dto.ResourceConfig;
import com.linkwork.model.dto.ResourceSpec;
import com.linkwork.model.dto.ServiceBuildRequest;
import com.linkwork.model.enums.DeployMode;
import com.linkwork.model.enums.PodMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 配置融合服务
 * 将请求参数与环境配置融合，生成 MergedConfig
 * 
 * 设计说明：
 * - 仅构建 Agent 镜像，Runner 由运行时 agent 启动
 * - token 放入 buildEnvVars，在 build.sh 执行前 export
 */
@Service
@Slf4j
public class ConfigMergeService {
    
    private final EnvConfig envConfig;
    private final QueueSelector queueSelector;
    private final ImageBuildConfig imageBuildConfig;
    
    public ConfigMergeService(EnvConfig envConfig, QueueSelector queueSelector, 
                              ImageBuildConfig imageBuildConfig) {
        this.envConfig = envConfig;
        this.queueSelector = queueSelector;
        this.imageBuildConfig = imageBuildConfig;
    }
    
    /**
     * 融合请求配置与环境配置
     */
    public MergedConfig merge(ServiceBuildRequest request) {
        log.info("Merging config for service {}", request.getServiceId());
        
        // 1. 决策 PodMode
        PodMode podMode = decidePodMode(request);
        
        // 2. 决策 Pod 数量
        int podCount = decidePodCount(request);
        
        // 3. 选择调度队列（仅 K8s 模式）
        String queueName = request.getDeployMode() == DeployMode.K8S 
            ? queueSelector.selectQueue(request.getPriority(), false)
            : null;
        
        // 4. 融合资源配置
        ResourceSpec agentResources = mergeResources(
            request.getResourceConfig(), envConfig.getDefaultResources().getAgent());
        ResourceSpec runnerResources = mergeResources(
            request.getResourceConfig(), envConfig.getDefaultResources().getRunner());
        
        // 5. 决定 Agent 镜像地址（使用默认配置）
        String agentBaseImage = envConfig.getImages().getAgent();
        
        // 6. 决定 Runner 镜像地址
        // - K8s + Sidecar 模式：使用 runnerBaseImage 或默认值
        // - 非 K8s 或 Alone 模式：不需要 Runner 镜像
        String runnerImage;
        if (request.getDeployMode() == DeployMode.K8S && podMode == PodMode.SIDECAR) {
            runnerImage = Optional.ofNullable(request.getRunnerBaseImage())
                .orElse(envConfig.getImages().getRunner());
        } else {
            runnerImage = null;  // 非 Sidecar 模式不需要 Runner 镜像
        }
        
        // 7. 构建融合配置
        return MergedConfig.builder()
            // 服务标识
            .serviceId(request.getServiceId())
            .userId(request.getUserId())
            .roleId(request.getRoleId())
            // 模式配置
            .deployMode(request.getDeployMode())
            .podMode(podMode)
            .podCount(podCount)
            // K8s 配置
            .namespace(envConfig.getCluster().getNamespace())
            .queueName(queueName)
            .priorityClassName(getPriorityClass(request.getPriority()))
            // 镜像配置
            .agentImage(agentBaseImage)
            .runnerImage(runnerImage)
            .buildEnvVars(request.getBuildEnvVars())
            .agentBaseImage(agentBaseImage)
            .imageRegistry(request.getImageRegistry())
            .imagePullPolicy(imageBuildConfig.getImagePullPolicy())
            .imagePullSecret(imageBuildConfig.getImagePullSecret())
            // Agent 启动脚本配置
            .mainPyUrl(envConfig.getAgentBootstrap().getMainPyUrl())
            // 文件放置配置
            .filePlacement(envConfig.getFilePlacement())
            // 资源配置
            .agentResources(agentResources)
            .runnerResources(runnerResources)
            // 网络配置
            .apiBaseUrl(envConfig.getNetwork().getApiBaseUrl())
            .wsBaseUrl(envConfig.getNetwork().getWsBaseUrl())
            .llmGatewayUrl(envConfig.getNetwork().getLlmGatewayUrl())
            .redisUrl(envConfig.getNetwork().getRedisUrl())
            // SSH 配置
            .sshPort(podMode == PodMode.SIDECAR ? envConfig.getSsh().getPort() : null)
            // Agent 启动配置（双容器模式）
            .workstationId(request.getServiceId())
            // 工作目录配置
            .workspaceSizeLimit(Optional.ofNullable(request.getWorkspaceSizeLimit()).orElse(10))
            // 任务元信息（可选）
            .description(request.getDescription())
            // 回调配置
            .callbackUrl(request.getCallbackUrl())
            // 快速恢复配置
            .preferredNode(request.getPreferredNode())
            // OSS 挂载配置
            .ossMount(envConfig.getOssMount())
            .build();
    }
    
    /**
     * 决策 PodMode
     */
    private PodMode decidePodMode(ServiceBuildRequest request) {
        // 1. 用户显式指定
        if (request.getPodMode() != null) {
            return request.getPodMode();
        }
        
        // 2. Compose 强制 Alone
        if (request.getDeployMode() == DeployMode.COMPOSE) {
            return PodMode.ALONE;
        }
        
        // 3. 使用默认值
        return envConfig.getPodModeRules().getDefaultMode();
    }
    
    /**
     * 决策 Pod 数量
     */
    private int decidePodCount(ServiceBuildRequest request) {
        // Compose 模式不支持多 Pod
        if (request.getDeployMode() == DeployMode.COMPOSE) {
            return 1;
        }
        
        // 默认 Pod 数量为 4
        int count = Optional.ofNullable(request.getPodCount()).orElse(4);
        return Math.min(Math.max(count, 1), 10);  // 限制 1-10
    }
    
    /**
     * 融合资源配置
     */
    private ResourceSpec mergeResources(ResourceConfig requested, ResourceSpec defaultSpec) {
        if (requested == null) {
            return defaultSpec;
        }
        return ResourceSpec.builder()
            .cpuRequest(Optional.ofNullable(requested.getCpuRequest()).orElse(defaultSpec.getCpuRequest()))
            .cpuLimit(Optional.ofNullable(requested.getCpuLimit()).orElse(defaultSpec.getCpuLimit()))
            .memoryRequest(Optional.ofNullable(requested.getMemoryRequest()).orElse(defaultSpec.getMemoryRequest()))
            .memoryLimit(Optional.ofNullable(requested.getMemoryLimit()).orElse(defaultSpec.getMemoryLimit()))
            .build();
    }
    
    /**
     * 获取优先级类
     */
    private String getPriorityClass(Integer priority) {
        int p = Optional.ofNullable(priority).orElse(50);
        if (p >= 90) return "critical-priority";
        if (p >= 70) return "high-priority";
        if (p >= 30) return "normal-priority";
        return "low-priority";
    }
}
