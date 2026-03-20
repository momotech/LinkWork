package com.linkwork.config;

import com.linkwork.model.dto.ResourceSpec;
import com.linkwork.model.enums.PodMode;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * 系统环境配置（从 YAML 加载）
 * 
 * 设计说明：
 * - 所有代码已打入镜像，不需要代码拉取配置
 * - 文件放置配置定义 token 和 ssh-key 的路径和权限
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "schedule")
public class EnvConfig {
    
    // 集群配置
    private ClusterConfig cluster = new ClusterConfig();
    
    // 镜像配置
    private ImagesConfig images = new ImagesConfig();
    
    // Agent 启动脚本配置
    private AgentBootstrapConfig agentBootstrap = new AgentBootstrapConfig();
    
    // 文件放置配置
    private FilePlacementConfig filePlacement = new FilePlacementConfig();
    
    // 网络配置
    private NetworkConfig network = new NetworkConfig();
    
    // SSH 配置
    private SshConfig ssh = new SshConfig();
    
    // 默认资源配置
    private DefaultResourcesConfig defaultResources = new DefaultResourcesConfig();
    
    // NFS 挂载配置（沿用 oss-mount YAML key 以兼容现有 K8s 配置）
    private OssMountConfig ossMount = new OssMountConfig();
    
    // 模式决策规则
    private PodModeRulesConfig podModeRules = new PodModeRulesConfig();
    
    @Data
    public static class ClusterConfig {
        private String namespace = "ai-worker";
        private String schedulerName = "volcano";
        private String kubeconfigPath;  // kubeconfig 文件路径
    }
    
    @Data
    public static class ImagesConfig {
        private String registry = "";
        private String agent = "ai-worker/agent-base:v1.0";  // Agent 基础镜像
        private String runner = "ai-worker/runner-base:v1.0";  // Runner 默认镜像（Sidecar 模式）
        private Map<String, String> defaultRunners = new HashMap<>();  // Runner 镜像（代码已内置）
    }
    
    /**
     * Agent 启动脚本配置
     * main.py 从链接下载，执行后启动 agent 和 executor 两个进程
     */
    @Data
    public static class AgentBootstrapConfig {
        private String mainPyUrl;  // main.py 下载链接
    }
    
    /**
     * 文件放置配置
     * - token: 仅 executor 用户可访问
     * - ssh-key: agent 和 executor 都可访问
     */
    @Data
    public static class FilePlacementConfig {
        // token 文件配置（仅 executor 可访问）
        private String tokenPath = "/workspace/.credentials/token";
        private String tokenDirMode = "700";
        private String tokenFileMode = "600";
        private String tokenOwner = "executor:executor";
        
        // SSH 密钥配置（agent 和 executor 都可访问）
        private String sshPath = "/workspace/.ssh";
        private String sshDirMode = "755";
        private String sshKeyMode = "600";      // 私钥
        private String sshPubMode = "644";      // 公钥
    }
    
    @Data
    public static class NetworkConfig {
        private String apiBaseUrl;
        private String wsBaseUrl;
        private String llmGatewayUrl;
        private String redisUrl;
    }
    
    @Data
    public static class SshConfig {
        private Integer port = 2222;
        private String keyType = "ed25519";
    }
    
    @Data
    public static class DefaultResourcesConfig {
        private ResourceSpec agent = ResourceSpec.builder()
            .cpuRequest("1").cpuLimit("2")
            .memoryRequest("2Gi").memoryLimit("4Gi")
            .build();
        private ResourceSpec runner = ResourceSpec.builder()
            .cpuRequest("1").cpuLimit("4")
            .memoryRequest("2Gi").memoryLimit("8Gi")
            .build();
    }
    
    /**
     * OSS 挂载配置
     * 通过 hostPath 将宿主机上的 ossfs 挂载目录映射到容器内
     *
     * 节点级挂载 (DaemonSet / ossfs):
     *   oss://robot-agent-files/system/      → hostPath/system
     *   oss://robot-agent-files/user-files/  → hostPath/user-files
     *   oss://robot-agent-files/workstation/ → hostPath/workstation
     *
     * 容器级挂载 (Pod hostPath → container):
     *   1. oss-data:        hostPath/system/{wsId}       → mountPath            (读写，产出物挂载根)
     *   2. oss-user-files:  hostPath/user-files           → /mnt/user-files  (读写，个人空间挂载根)
     *   3. oss-workstation: hostPath/workstation/{wsId}   → /mnt/workstation (读写，岗位空间挂载根)
     */
    @Data
    public static class OssMountConfig {
        /** 是否启用 NFS 挂载 */
        private boolean enabled = false;
        /** 宿主机 NFS 挂载根目录 */
        private String hostPath = "/mnt/oss/robot-agent-files";
        /** 容器内主挂载路径（产出物挂载根） */
        private String mountPath = "/data/oss/robot";
        /** 主挂载是否只读 */
        private boolean readOnly = false;

        /** user-files 容器内挂载路径（记忆-个人空间挂载根） */
        private String userFilesMountPath = "/mnt/user-files";
        /** workstation 容器内挂载路径（记忆-岗位空间挂载根） */
        private String workstationMountPath = "/mnt/workstation";
    }
    
    @Data
    public static class PodModeRulesConfig {
        private PodMode defaultMode = PodMode.SIDECAR;
        private Map<String, PodMode> overrides = new HashMap<>();
    }
}
