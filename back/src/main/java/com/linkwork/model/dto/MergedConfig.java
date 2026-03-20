package com.linkwork.model.dto;

import com.linkwork.config.EnvConfig.FilePlacementConfig;
import com.linkwork.config.EnvConfig.OssMountConfig;
import com.linkwork.model.enums.DeployMode;
import com.linkwork.model.enums.PodMode;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 融合后的配置（内部使用）
 * 
 * 设计说明：
 * - 镜像在 Build 时根据环境变量动态构建
 * - token 写入容器后仅 executor 可访问
 */
@Data
@Builder
public class MergedConfig {
    // 服务标识
    private String serviceId;
    private String userId;
    
    // 岗位信息（用于运行时 MCP 配置拉取）
    private Long roleId;

    // 模式配置
    private DeployMode deployMode;
    private PodMode podMode;
    private Integer podCount;
    
    // K8s 调度配置
    private String namespace;
    private String queueName;
    private String priorityClassName;
    
    // ========== 镜像配置 ==========
    /**
     * Agent 镜像地址
     * 在镜像构建后设置为构建的镜像地址
     */
    private String agentImage;
    
    /**
     * Runner 镜像地址（Sidecar 模式）
     * 在镜像构建后设置为构建的镜像地址
     */
    private String runnerImage;
    
    /**
     * 镜像构建耗时（毫秒）
     */
    private Long imageBuildDurationMs;
    
    /**
     * 镜像拉取策略（Always/IfNotPresent/Never）
     */
    private String imagePullPolicy;
    
    /**
     * K8s 拉取私有镜像的 Secret 名称
     */
    private String imagePullSecret;
    
    // ========== 构建参数（用于镜像构建）==========
    /**
     * 环境变量列表（用于镜像构建）
     */
    private Map<String, Object> buildEnvVars;
    
    /**
     * Agent 基础镜像
     */
    private String agentBaseImage;
    
    /**
     * Runner 基础镜像（Sidecar 模式）
     */
    private String runnerBaseImage;
    
    /**
     * 镜像仓库地址
     */
    private String imageRegistry;
    
    // Agent 启动脚本配置
    private String mainPyUrl;       // main.py 下载链接（启动 agent+executor）
    
    // 凭证配置
    private String token;           // API 凭证（写入容器，仅 executor 可访问）
    
    // 文件放置配置
    private FilePlacementConfig filePlacement;
    
    // 资源配置
    private ResourceSpec agentResources;
    private ResourceSpec runnerResources;
    
    // 网络配置
    private String apiBaseUrl;
    private String wsBaseUrl;
    private String llmGatewayUrl;
    private String redisUrl;

    // SSH 配置（仅 Sidecar）
    private Integer sshPort;
    
    // ========== Agent 启动配置（双容器模式）==========
    /**
     * 工位 ID (WORKSTATION_ID 环境变量，默认使用 serviceId)
     */
    private String workstationId;

    /**
     * Agent config.json 内容（用于创建 ConfigMap 挂载到 /opt/agent/config.json）
     */
    private String configJson;

    // 执行限制
    private Integer maxSteps;
    private Integer maxRuntimeSeconds;
    private Integer workspaceSizeLimit;
    
    // 任务元信息
    private String model;
    private String runnerType;
    private String description;
    
    // 回调配置
    private String callbackUrl;
    
    // 快速恢复配置
    private String preferredNode;       // 优先调度节点（Resume 时使用）
    
    // OSS 挂载配置
    private OssMountConfig ossMount;
}
