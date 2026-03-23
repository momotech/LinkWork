package com.linkwork.service;

import com.linkwork.config.EnvConfig.OssMountConfig;
import com.linkwork.model.dto.MergedConfig;
import com.linkwork.model.enums.PodMode;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pod Spec 生成器
 * 
 * 双容器模式 (Sidecar):
 *   Agent 容器:  /opt/agent/start-dual.sh   → 密钥管理 → 等 Runner SSH → 启动 zzd + worker
 *   Runner 容器: /opt/runner/start-runner.sh → 等公钥 → 配置 authorized_keys → sshd -D
 *   
 * 单容器模式 (Alone):
 *   Agent 容器:  /opt/agent/start-single.sh  → 本地模式启动
 *   
 * 共享卷:
 *   - shared-keys (emptyDir/Memory): Agent 写公钥 → Runner 读取
 *   - workspace (emptyDir/PVC): 工作目录
 *   
 * ConfigMap:
 *   - svc-{serviceId}-agent-config: config.json → /opt/agent/config.json
 *   - runner-start-script: start-runner.sh → /opt/runner/start-runner.sh
 */
@Component("legacyPodSpecGenerator")
@Slf4j
public class PodSpecGenerator {

    private static final String PERMISSION_INIT_CPU_REQUEST = "100m";
    private static final String PERMISSION_INIT_CPU_LIMIT = "500m";
    private static final String PERMISSION_INIT_MEMORY_REQUEST = "128Mi";
    private static final String PERMISSION_INIT_MEMORY_LIMIT = "512Mi";

    /** Runner 启动脚本 ConfigMap 名称（集群级共享） */
    public static final String RUNNER_SCRIPT_CONFIGMAP = "runner-start-script";
    
    /** Runner 启动脚本在 ConfigMap 中的 key */
    public static final String RUNNER_SCRIPT_KEY = "start-runner.sh";

    /** zzd 调用 /api/v1/tasks/{taskId}/git-token 的服务身份 token（可选） */
    @Value("${robot.zzd.api-server-token:}")
    private String zzdApiServerToken;

    /** Claude/LiteLLM 运行时网关配置 */
    @Value("${ANTHROPIC_BASE_URL:${robot.litellm.base-url:http://localhost:4000}}")
    private String anthropicBaseUrl;

    @Value("${ANTHROPIC_AUTH_TOKEN:${robot.litellm.api-key:}}")
    private String anthropicAuthToken;

    @Value("${ANTHROPIC_API_KEY:${robot.litellm.api-key:}}")
    private String anthropicApiKey;

    @Value("${LITELLM_BASE_URL:${robot.litellm.base-url:http://localhost:4000}}")
    private String litellmBaseUrl;

    @Value("${LITELLM_API_KEY:${robot.litellm.api-key:}}")
    private String litellmApiKey;

    @Value("${OPENAI_API_KEY:${robot.litellm.api-key:}}")
    private String openaiApiKey;

    @Value("${ANTHROPIC_MODEL:${robot.litellm.default-chat-model:openrouter/anthropic/claude-sonnet-4.5}}")
    private String anthropicModel;
    
    /**
     * 生成 Pod Spec（根据模式选择）
     */
    public Pod generate(MergedConfig config, int podIndex) {
        return config.getPodMode() == PodMode.SIDECAR 
            ? generateSidecarPod(config, podIndex)
            : generateAlonePod(config, podIndex);
    }
    
    /**
     * 获取 Agent ConfigMap 名称
     */
    public static String agentConfigMapName(String serviceId) {
        return "svc-" + serviceId + "-agent-config";
    }
    
    /**
     * 生成 Sidecar 模式 Pod
     * - Agent 容器：root 启动, /opt/agent/start-dual.sh
     * - Runner 容器：root 启动, /opt/runner/start-runner.sh (via ConfigMap)
     * - 共享卷: /shared-keys (emptyDir/Memory), /workspace
     */
    private Pod generateSidecarPod(MergedConfig config, int podIndex) {
        String podName = "svc-" + config.getServiceId() + "-" + podIndex;
        String podGroupName = "svc-" + config.getServiceId() + "-pg";
        
        log.info("Generating Sidecar Pod: {}, preferredNode: {}", podName, config.getPreferredNode());
        
        PodBuilder builder = new PodBuilder()
            .withNewMetadata()
                .withName(podName)
                .withNamespace(config.getNamespace())
                .addToLabels("app", "ai-worker-service")
                .addToLabels("service-id", config.getServiceId())
                .addToLabels("user-id", config.getUserId())
                .addToLabels("pod-index", String.valueOf(podIndex))
                .addToLabels("pod-mode", "sidecar")
                // Volcano 注解（关联 PodGroup）
                .addToAnnotations("scheduling.k8s.io/group-name", podGroupName)
                .addToAnnotations("scheduling.volcano.sh/group-name", podGroupName)
                .addToAnnotations("volcano.sh/queue-name", config.getQueueName())
            .endMetadata()
            .withNewSpec()
                .withSchedulerName("volcano")
                .withPriorityClassName(config.getPriorityClassName())
                .withRestartPolicy("Never")
                .withTerminationGracePeriodSeconds(30L)
                // 私有镜像拉取凭证
                .withImagePullSecrets(buildImagePullSecrets(config))
                // 主容器
                .addToContainers(buildSidecarAgentContainer(config))
                .addToContainers(buildSidecarRunnerContainer(config))
                // Volumes
                .addAllToVolumes(buildSidecarVolumes(config))
            .endSpec();
        
        // 权限初始化 InitContainer：user-files / workstation 挂载根设为仅 x
        Container permInit = buildPermissionInitContainer(config);
        if (permInit != null) {
            builder.editSpec()
                .addToInitContainers(permInit)
            .endSpec();
        }
        
        // 添加节点亲和配置（用于快速重启）
        if (StringUtils.hasText(config.getPreferredNode())) {
            builder.editSpec()
                .withAffinity(buildPreferredNodeAffinity(config.getPreferredNode()))
            .endSpec();
        }
        
        return builder.build();
    }
    
    /**
     * 生成 Alone 模式 Pod
     * - 单容器：/opt/agent/start-single.sh (ZZD_MODE=local)
     */
    private Pod generateAlonePod(MergedConfig config, int podIndex) {
        String podName = "svc-" + config.getServiceId() + "-" + podIndex;
        String podGroupName = "svc-" + config.getServiceId() + "-pg";
        
        log.info("Generating Alone Pod: {}, preferredNode: {}", podName, config.getPreferredNode());
        
        PodBuilder builder = new PodBuilder()
            .withNewMetadata()
                .withName(podName)
                .withNamespace(config.getNamespace())
                .addToLabels("app", "ai-worker-service")
                .addToLabels("service-id", config.getServiceId())
                .addToLabels("user-id", config.getUserId())
                .addToLabels("pod-index", String.valueOf(podIndex))
                .addToLabels("pod-mode", "alone")
                // Volcano 注解
                .addToAnnotations("scheduling.k8s.io/group-name", podGroupName)
                .addToAnnotations("scheduling.volcano.sh/group-name", podGroupName)
                .addToAnnotations("volcano.sh/queue-name", config.getQueueName())
            .endMetadata()
            .withNewSpec()
                .withSchedulerName("volcano")
                .withPriorityClassName(config.getPriorityClassName())
                .withRestartPolicy("Never")
                .withTerminationGracePeriodSeconds(30L)
                // 私有镜像拉取凭证
                .withImagePullSecrets(buildImagePullSecrets(config))
                // 单容器
                .addToContainers(buildAloneContainer(config))
                // Volumes
                .addAllToVolumes(buildAloneVolumes(config))
            .endSpec();
        
        // 权限初始化 InitContainer：user-files / workstation 挂载根设为仅 x
        Container permInit = buildPermissionInitContainer(config);
        if (permInit != null) {
            builder.editSpec()
                .addToInitContainers(permInit)
            .endSpec();
        }
        
        // 添加节点亲和配置（用于快速重启）
        if (StringUtils.hasText(config.getPreferredNode())) {
            builder.editSpec()
                .withAffinity(buildPreferredNodeAffinity(config.getPreferredNode()))
            .endSpec();
        }
        
        return builder.build();
    }
    
    // ==================== Sidecar 模式容器构建 ====================
    
    /**
     * Sidecar 模式 Agent 容器
     * 
     * 启动命令: /opt/agent/start-dual.sh
     * 流程: SSH 密钥管理 → 写公钥到 /shared-keys → 等 Runner SSH → 启动 zzd + worker
     * 
     * 必须以 root 启动 (写 /etc/zzd、起 zzd、再 sudo -u agent 拉 worker)
     */
    private Container buildSidecarAgentContainer(MergedConfig config) {
        List<EnvVar> envVars = new ArrayList<>();
        
        // ---- 必填环境变量 ----
        envVars.add(new EnvVar("WORKSTATION_ID", 
            config.getWorkstationId() != null ? config.getWorkstationId() : config.getServiceId(), null));
        if (config.getRedisUrl() != null) {
            envVars.add(new EnvVar("REDIS_URL", config.getRedisUrl(), null));
        }
        envVars.add(new EnvVar("CONFIG_FILE", "/opt/agent/config.json", null));
        envVars.add(new EnvVar("IDLE_TIMEOUT", "86400", null));
        
        // ---- 双容器模式配置 ----
        // 同 Pod 内 Runner，通过 localhost 连接
        envVars.add(new EnvVar("ZZD_RUNNER_HOST", "localhost", null));
        appendRuntimeGitTokenEnv(envVars, config, true);
        
        // ---- 服务标识 ----
        envVars.add(new EnvVar("SERVICE_ID", config.getServiceId(), null));
        envVars.add(new EnvVar("USER_ID", config.getUserId(), null));
        // 注意: 不注入 ZZ_MODE / ZZD_MODE / SSH_PORT
        // - ZZD_MODE 由 start-dual.sh 内部设置为 sandbox（见 zzd.md §运行模式）
        // - SSH 端口由 zzd 配置模型内部管理（runner_port），非 Pod env var
        
        // ---- Downward API: Pod 名称 ----
        envVars.add(new EnvVarBuilder()
            .withName("POD_NAME")
            .withNewValueFrom()
                .withNewFieldRef()
                    .withFieldPath("metadata.name")
                .endFieldRef()
            .endValueFrom()
            .build());
        
        // ---- 可选环境变量 ----
        if (config.getMainPyUrl() != null) {
            envVars.add(new EnvVar("MAIN_PY_URL", config.getMainPyUrl(), null));
        }
        if (config.getApiBaseUrl() != null) {
            envVars.add(new EnvVar("API_BASE_URL", config.getApiBaseUrl(), null));
            envVars.add(new EnvVar("RUNTIME_GIT_API_FALLBACK_URL", config.getApiBaseUrl(), null));
        }
        if (config.getWsBaseUrl() != null) {
            envVars.add(new EnvVar("WS_BASE_URL", config.getWsBaseUrl(), null));
        }
        if (config.getLlmGatewayUrl() != null) {
            envVars.add(new EnvVar("LLM_GATEWAY_URL", config.getLlmGatewayUrl(), null));
        }
        if (config.getRoleId() != null) {
            envVars.add(new EnvVar("ROLE_ID", String.valueOf(config.getRoleId()), null));
        }
        appendClaudeRuntimeEnv(envVars);
        
        String agentConfigMap = agentConfigMapName(config.getServiceId());
        
        ContainerBuilder agentBuilder = new ContainerBuilder()
            .withName("agent")
            .withImage(config.getAgentImage())
            .withImagePullPolicy(config.getImagePullPolicy() != null ? config.getImagePullPolicy() : "IfNotPresent")
            // ★ 启动命令: start-dual.sh（不要跑 build.sh）
            .withCommand("/opt/agent/start-dual.sh")
            .withEnv(envVars)
            // ★ 以 root 启动（脚本里要写 /etc/zzd、起 zzd、再 sudo -u agent 拉 worker）
            // NET_ADMIN: ENABLE_NETWORK_FIREWALL=true 时 start.sh 需要 iptables 配置 agent 用户网络白名单
            .withNewSecurityContext()
                .withRunAsUser(0L)
                .withNewCapabilities()
                    .addToAdd("NET_ADMIN")
                .endCapabilities()
            .endSecurityContext()
            // ---- Volume Mounts ----
            .addToVolumeMounts(new VolumeMountBuilder()
                .withName("workspace")
                .withMountPath("/workspace")
                .withReadOnly(false)
                .build())
            .addToVolumeMounts(new VolumeMountBuilder()
                .withName("shared-keys")
                .withMountPath("/shared-keys")
                .withReadOnly(false)
                .build())
            // ★ 挂载配置文件: ConfigMap subPath → /opt/agent/config.json（只读）
            .addToVolumeMounts(new VolumeMountBuilder()
                .withName("agent-config")
                .withMountPath("/opt/agent/config.json")
                .withSubPath("config.json")
                .withReadOnly(true)
                .build())
            // ---- Resources ----
            .withNewResources()
                .addToRequests("cpu", new Quantity(config.getAgentResources().getCpuRequest()))
                .addToRequests("memory", new Quantity(config.getAgentResources().getMemoryRequest()))
                .addToLimits("cpu", new Quantity(config.getAgentResources().getCpuLimit()))
                .addToLimits("memory", new Quantity(config.getAgentResources().getMemoryLimit()))
            .endResources();
        
        // OSS 挂载
        addOssVolumeMount(agentBuilder, config);
        
        return agentBuilder.build();
    }
    
    /**
     * Sidecar 模式 Runner 容器
     * 
     * 使用 Rocky 基础镜像（非独立 Runner 镜像）
     * 启动脚本通过 ConfigMap 挂载: runner-start-script → /opt/runner/start-runner.sh
     * 
     * 流程: 校验 sshd 环境 → 创建 momobot → 等公钥 → 写 authorized_keys → sshd -D -e
     */
    private Container buildSidecarRunnerContainer(MergedConfig config) {
        List<EnvVar> envVars = new ArrayList<>();
        envVars.add(new EnvVar("SERVICE_ID", config.getServiceId(), null));
        envVars.add(new EnvVar("USER_ID", config.getUserId(), null));
        // 公钥等待超时（秒）
        envVars.add(new EnvVar("PUBKEY_TIMEOUT", "120", null));
        // Downward API
        envVars.add(new EnvVarBuilder()
            .withName("POD_NAME")
            .withNewValueFrom()
                .withNewFieldRef()
                    .withFieldPath("metadata.name")
                .endFieldRef()
            .endValueFrom()
            .build());
        if (config.getApiBaseUrl() != null) {
            envVars.add(new EnvVar("API_BASE_URL", config.getApiBaseUrl(), null));
        }
        
        ContainerBuilder runnerBuilder = new ContainerBuilder()
            .withName("runner")
            .withImage(config.getRunnerImage())  // Rocky 基础镜像
            .withImagePullPolicy(config.getImagePullPolicy() != null ? config.getImagePullPolicy() : "IfNotPresent")
            // ★ 启动命令: start-runner.sh（通过 ConfigMap 挂载）
            .withCommand("/opt/runner/start-runner.sh")
            .withEnv(envVars)
            // Runner 也以 root 启动（需要 ssh-keygen -A、创建用户、启动 sshd）
            .withNewSecurityContext()
                .withRunAsUser(0L)
            .endSecurityContext()
            // ★ SSH 端口 22（sshd 默认端口，Agent 通过 localhost:22 连接）
            .addToPorts(new ContainerPort(22, null, null, "ssh", "TCP"))
            // ---- Volume Mounts ----
            .addToVolumeMounts(new VolumeMountBuilder()
                .withName("workspace")
                .withMountPath("/workspace")
                .withReadOnly(false)
                .build())
            .addToVolumeMounts(new VolumeMountBuilder()
                .withName("shared-keys")
                .withMountPath("/shared-keys")
                .withReadOnly(false)
                .build())
            // ★ 挂载启动脚本: ConfigMap → /opt/runner/start-runner.sh
            .addToVolumeMounts(new VolumeMountBuilder()
                .withName("runner-scripts")
                .withMountPath("/opt/runner/start-runner.sh")
                .withSubPath(RUNNER_SCRIPT_KEY)
                .withReadOnly(true)
                .build())
            // ---- Resources ----
            .withNewResources()
                .addToRequests("cpu", new Quantity(config.getRunnerResources().getCpuRequest()))
                .addToRequests("memory", new Quantity(config.getRunnerResources().getMemoryRequest()))
                .addToLimits("cpu", new Quantity(config.getRunnerResources().getCpuLimit()))
                .addToLimits("memory", new Quantity(config.getRunnerResources().getMemoryLimit()))
            .endResources()
            // ★ 就绪探针：SSH 22 端口 TCP 检查
            // Runner 需要: dnf install sshd + 等待 Agent 公钥 + 启动 sshd，首次可能需要较长时间
            .withNewReadinessProbe()
                .withNewTcpSocket()
                    .withNewPort(22)
                .endTcpSocket()
                .withInitialDelaySeconds(30)
                .withPeriodSeconds(10)
                .withFailureThreshold(30)
            .endReadinessProbe();
        
        // OSS 挂载
        addOssVolumeMount(runnerBuilder, config);
        
        return runnerBuilder.build();
    }
    
    // ==================== Alone 模式容器构建 ====================
    
    /**
     * Alone 模式容器
     * 启动命令: /opt/agent/start-single.sh (ZZD_MODE=local，不需要 Runner)
     */
    private Container buildAloneContainer(MergedConfig config) {
        List<EnvVar> envVars = new ArrayList<>();
        
        // 必填环境变量
        envVars.add(new EnvVar("WORKSTATION_ID",
            config.getWorkstationId() != null ? config.getWorkstationId() : config.getServiceId(), null));
        if (config.getRedisUrl() != null) {
            envVars.add(new EnvVar("REDIS_URL", config.getRedisUrl(), null));
        }
        envVars.add(new EnvVar("CONFIG_FILE", "/opt/agent/config.json", null));
        envVars.add(new EnvVar("IDLE_TIMEOUT", "86400", null));
        
        // 服务标识
        envVars.add(new EnvVar("SERVICE_ID", config.getServiceId(), null));
        envVars.add(new EnvVar("USER_ID", config.getUserId(), null));
        appendRuntimeGitTokenEnv(envVars, config, false);
        // 注意: 不注入 ZZD_MODE，start-single.sh 内部设置为 local
        // Downward API
        envVars.add(new EnvVarBuilder()
            .withName("POD_NAME")
            .withNewValueFrom()
                .withNewFieldRef()
                    .withFieldPath("metadata.name")
                .endFieldRef()
            .endValueFrom()
            .build());
        // 可选环境变量
        if (config.getMainPyUrl() != null) {
            envVars.add(new EnvVar("MAIN_PY_URL", config.getMainPyUrl(), null));
        }
        if (config.getApiBaseUrl() != null) {
            envVars.add(new EnvVar("API_BASE_URL", config.getApiBaseUrl(), null));
            envVars.add(new EnvVar("RUNTIME_GIT_API_FALLBACK_URL", config.getApiBaseUrl(), null));
        }
        if (config.getWsBaseUrl() != null) {
            envVars.add(new EnvVar("WS_BASE_URL", config.getWsBaseUrl(), null));
        }
        if (config.getLlmGatewayUrl() != null) {
            envVars.add(new EnvVar("LLM_GATEWAY_URL", config.getLlmGatewayUrl(), null));
        }
        if (config.getRoleId() != null) {
            envVars.add(new EnvVar("ROLE_ID", String.valueOf(config.getRoleId()), null));
        }
        appendClaudeRuntimeEnv(envVars);
        
        String agentConfigMap = agentConfigMapName(config.getServiceId());
        
        
        ContainerBuilder aloneBuilder = new ContainerBuilder()
            .withName("agent")
            .withImage(config.getAgentImage())
            .withImagePullPolicy(config.getImagePullPolicy() != null ? config.getImagePullPolicy() : "IfNotPresent")
            // ★ 启动命令: start-single.sh
            .withCommand("/opt/agent/start-single.sh")
            .withEnv(envVars)
            // root 启动; NET_ADMIN: ENABLE_NETWORK_FIREWALL=true 时 start.sh 需要 iptables 配置 agent 用户网络白名单
            .withNewSecurityContext()
                .withRunAsUser(0L)
                .withNewCapabilities()
                    .addToAdd("NET_ADMIN")
                .endCapabilities()
            .endSecurityContext()
            .addToVolumeMounts(new VolumeMountBuilder()
                .withName("workspace")
                .withMountPath("/workspace")
                .withReadOnly(false)
                .build())
            // 挂载配置文件
            .addToVolumeMounts(new VolumeMountBuilder()
                .withName("agent-config")
                .withMountPath("/opt/agent/config.json")
                .withSubPath("config.json")
                .withReadOnly(true)
                .build())
            .withNewResources()
                .addToRequests("cpu", new Quantity(config.getAgentResources().getCpuRequest()))
                .addToRequests("memory", new Quantity(config.getAgentResources().getMemoryRequest()))
                .addToLimits("cpu", new Quantity(config.getAgentResources().getCpuLimit()))
                .addToLimits("memory", new Quantity(config.getAgentResources().getMemoryLimit()))
            .endResources();
        
        // OSS 挂载
        addOssVolumeMount(aloneBuilder, config);
        
        return aloneBuilder.build();
    }

    /**
     * 注入运行时 Git Token 相关环境变量。
     * - sidecar 模式默认强制 git 路由到 local，避免命中 runner 丢失 token 注入。
     * - API_BASE_URL 与 ZZD_API_SERVER_URL 同时下发：脚本可兜底回退。
     */
    private void appendRuntimeGitTokenEnv(List<EnvVar> envVars, MergedConfig config, boolean sidecarMode) {
        envVars.add(new EnvVar("ZZD_ENABLE_GIT_TOKEN", "true", null));
        if (sidecarMode) {
            envVars.add(new EnvVar("ZZD_FORCE_GIT_LOCAL_ROUTE", "true", null));
        }
        if (StringUtils.hasText(config.getApiBaseUrl())) {
            envVars.add(new EnvVar("ZZD_API_SERVER_URL", config.getApiBaseUrl(), null));
        }
        if (StringUtils.hasText(zzdApiServerToken)) {
            envVars.add(new EnvVar("ZZD_API_SERVER_TOKEN", zzdApiServerToken, null));
        }
    }

    /**
     * 注入 Claude/LiteLLM 运行时所需环境变量。
     * 避免 agent 子进程缺失 base url / key 后回退到 localhost:4000。
     */
    private void appendClaudeRuntimeEnv(List<EnvVar> envVars) {
        if (StringUtils.hasText(anthropicBaseUrl)) {
            envVars.add(new EnvVar("ANTHROPIC_BASE_URL", anthropicBaseUrl, null));
        }
        if (StringUtils.hasText(anthropicAuthToken)) {
            envVars.add(new EnvVar("ANTHROPIC_AUTH_TOKEN", anthropicAuthToken, null));
        }
        if (StringUtils.hasText(anthropicApiKey)) {
            envVars.add(new EnvVar("ANTHROPIC_API_KEY", anthropicApiKey, null));
        }
        if (StringUtils.hasText(litellmBaseUrl)) {
            envVars.add(new EnvVar("LITELLM_BASE_URL", litellmBaseUrl, null));
        }
        if (StringUtils.hasText(litellmApiKey)) {
            envVars.add(new EnvVar("LITELLM_API_KEY", litellmApiKey, null));
        }
        if (StringUtils.hasText(openaiApiKey)) {
            envVars.add(new EnvVar("OPENAI_API_KEY", openaiApiKey, null));
        }
        if (StringUtils.hasText(anthropicModel)) {
            envVars.add(new EnvVar("ANTHROPIC_MODEL", anthropicModel, null));
        }
    }
    
    // ==================== Volume 构建 ====================
    
    /**
     * Sidecar 模式 Volumes
     * - workspace: 共享工作目录 (emptyDir)
     * - shared-keys: Agent/Runner 传递公钥 (emptyDir/Memory, 每次 Pod 重建清空)
     * - agent-config: ConfigMap, 挂载 config.json 到 /opt/agent/config.json
     * - runner-scripts: ConfigMap, 挂载 start-runner.sh 到 /opt/runner/start-runner.sh
     */
    private List<Volume> buildSidecarVolumes(MergedConfig config) {
        List<Volume> volumes = new ArrayList<>();
        
        // 共享工作目录
        volumes.add(new VolumeBuilder()
            .withName("workspace")
            .withNewEmptyDir()
                .withSizeLimit(new Quantity(config.getWorkspaceSizeLimit() + "Gi"))
            .endEmptyDir()
            .build());
        
        // ★ 共享密钥卷（emptyDir/Memory，Agent 写公钥 → Runner 读取）
        volumes.add(new VolumeBuilder()
            .withName("shared-keys")
            .withNewEmptyDir()
                .withMedium("Memory")
            .endEmptyDir()
            .build());
        
        // ★ Agent 配置文件（ConfigMap → /opt/agent/config.json）
        String agentConfigMap = agentConfigMapName(config.getServiceId());
        volumes.add(new VolumeBuilder()
            .withName("agent-config")
            .withNewConfigMap()
                .withName(agentConfigMap)
                .addNewItem()
                    .withKey("config.json")
                    .withPath("config.json")
                .endItem()
            .endConfigMap()
            .build());
        
        // ★ Runner 启动脚本（ConfigMap → /opt/runner/start-runner.sh, 0755 可执行）
        volumes.add(new VolumeBuilder()
            .withName("runner-scripts")
            .withNewConfigMap()
                .withName(RUNNER_SCRIPT_CONFIGMAP)
                .withDefaultMode(0755)
                .addNewItem()
                    .withKey(RUNNER_SCRIPT_KEY)
                    .withPath(RUNNER_SCRIPT_KEY)
                .endItem()
            .endConfigMap()
            .build());
        
        // OSS 挂载
        addOssVolume(volumes, config);
        
        return volumes;
    }
    
    /**
     * Alone 模式 Volumes
     */
    private List<Volume> buildAloneVolumes(MergedConfig config) {
        List<Volume> volumes = new ArrayList<>();
        
        // 工作目录
        volumes.add(new VolumeBuilder()
            .withName("workspace")
            .withNewEmptyDir()
                .withSizeLimit(new Quantity(config.getWorkspaceSizeLimit() + "Gi"))
            .endEmptyDir()
            .build());
        
        // Agent 配置文件（ConfigMap）
        String agentConfigMap = agentConfigMapName(config.getServiceId());
        volumes.add(new VolumeBuilder()
            .withName("agent-config")
            .withNewConfigMap()
                .withName(agentConfigMap)
                .addNewItem()
                    .withKey("config.json")
                    .withPath("config.json")
                .endItem()
            .endConfigMap()
            .build());
        
        // OSS 挂载
        addOssVolume(volumes, config);
        
        return volumes;
    }
    
    // ==================== 权限初始化 ====================
    
    /**
     * 构建权限初始化 InitContainer
     *
     * 容器启动前设置 NFS 挂载目录权限：
     *   - user-files、workstation → 0711（owner rwx / others x-only），
     *     非 root 用户无法 ls 挂载根，只能通过 zzd 创建的 symlink 访问被授权的子目录。
     *   - oss-data（产出物 /data/oss/robot）→ 0777，agent worker 启动时需要写入探测，
     *     NFS 上新建目录默认 755，非 root 用户无法写入，需要显式放开。
     */
    private Container buildPermissionInitContainer(MergedConfig config) {
        OssMountConfig ossMount = config.getOssMount();
        if (ossMount == null || !ossMount.isEnabled()) {
            return null;
        }

        String cmd = String.format("chmod 0777 %s && chmod 0711 %s %s",
            ossMount.getMountPath(),
            ossMount.getUserFilesMountPath(),
            ossMount.getWorkstationMountPath());

        return new ContainerBuilder()
            .withName("permission-init")
            .withImage(config.getAgentImage())
            .withCommand("sh", "-c", cmd)
            .withNewSecurityContext()
                .withRunAsUser(0L)
            .endSecurityContext()
            .withNewResources()
                .addToRequests("cpu", new Quantity(PERMISSION_INIT_CPU_REQUEST))
                .addToRequests("memory", new Quantity(PERMISSION_INIT_MEMORY_REQUEST))
                .addToLimits("cpu", new Quantity(PERMISSION_INIT_CPU_LIMIT))
                .addToLimits("memory", new Quantity(PERMISSION_INIT_MEMORY_LIMIT))
            .endResources()
            .addToVolumeMounts(new VolumeMountBuilder()
                .withName("oss-data")
                .withMountPath(ossMount.getMountPath())
                .withMountPropagation("HostToContainer")
                .build())
            .addToVolumeMounts(new VolumeMountBuilder()
                .withName("oss-user-files")
                .withMountPath(ossMount.getUserFilesMountPath())
                .withMountPropagation("HostToContainer")
                .build())
            .addToVolumeMounts(new VolumeMountBuilder()
                .withName("oss-workstation")
                .withMountPath(ossMount.getWorkstationMountPath())
                .withMountPropagation("HostToContainer")
                .build())
            .build();
    }
    
    // ==================== OSS 挂载 ====================
    
    /**
     * 添加 OSS hostPath Volumes (3 个挂载点)
     *
     * 节点上 ossfs 分三路挂载:
     *   oss://robot-agent-files/system/      → {hostPath}/system
     *   oss://robot-agent-files/user-files/  → {hostPath}/user-files
     *   oss://robot-agent-files/workstation/ → {hostPath}/workstation
     *
     * 容器级:
     *   1. oss-data:        {hostPath}/system/{wsId}       → /data/oss/robot     (产出物，读写)
     *   2. oss-user-files:  {hostPath}/user-files           → /mnt/user-files   (个人空间，读写)
     *   3. oss-workstation: {hostPath}/workstation/{wsId}   → /mnt/workstation  (岗位空间，读写)
     */
    private void addOssVolume(List<Volume> volumes, MergedConfig config) {
        OssMountConfig ossMount = config.getOssMount();
        if (ossMount == null || !ossMount.isEnabled()) {
            return;
        }
        
        String workstationId = config.getWorkstationId() != null
            ? config.getWorkstationId() : config.getServiceId();
        String hostBase = ossMount.getHostPath();
        
        // 1. oss-data: {hostPath}/system/{workstationId} → /data/oss/robot
        String systemHostPath = hostBase + "/system/" + workstationId;
        volumes.add(new VolumeBuilder()
            .withName("oss-data")
            .withNewHostPath()
                .withPath(systemHostPath)
                .withType("DirectoryOrCreate")
            .endHostPath()
            .build());
        log.info("Added OSS volume [oss-data]: hostPath={}, mountPath={}", systemHostPath, ossMount.getMountPath());
        
        // 2. oss-user-files: {hostPath}/user-files → /mnt/user-files
        String userFilesHostPath = hostBase + "/user-files";
        volumes.add(new VolumeBuilder()
            .withName("oss-user-files")
            .withNewHostPath()
                .withPath(userFilesHostPath)
                .withType("DirectoryOrCreate")
            .endHostPath()
            .build());
        log.info("Added OSS volume [oss-user-files]: hostPath={}, mountPath={}", 
            userFilesHostPath, ossMount.getUserFilesMountPath());
        
        // 3. oss-workstation: {hostPath}/workstation/{workstationId} → /mnt/workstation
        String workstationHostPath = hostBase + "/workstation/" + workstationId;
        volumes.add(new VolumeBuilder()
            .withName("oss-workstation")
            .withNewHostPath()
                .withPath(workstationHostPath)
                .withType("DirectoryOrCreate")
            .endHostPath()
            .build());
        log.info("Added OSS volume [oss-workstation]: hostPath={}, mountPath={}", 
            workstationHostPath, ossMount.getWorkstationMountPath());
    }
    
    /**
     * 为容器添加 OSS VolumeMounts (全部读写，权限由 zzd 在任务生命周期内通过 symlink 控制)
     *
     *   oss-data:        /data/oss/robot     → 产出物挂载根 (读写)
     *   oss-user-files:  /mnt/user-files    → 个人空间 (读写)
     *   oss-workstation: /mnt/workstation   → 岗位空间 (读写)
     */
    private void addOssVolumeMount(ContainerBuilder builder, MergedConfig config) {
        OssMountConfig ossMount = config.getOssMount();
        if (ossMount == null || !ossMount.isEnabled()) {
            return;
        }
        
        // 1. oss-data (产出物，读写)
        builder.addToVolumeMounts(new VolumeMountBuilder()
            .withName("oss-data")
            .withMountPath(ossMount.getMountPath())
            .withReadOnly(ossMount.isReadOnly())
            .withMountPropagation("HostToContainer")
            .build());
        
        // 2. oss-user-files (个人空间，读写)
        builder.addToVolumeMounts(new VolumeMountBuilder()
            .withName("oss-user-files")
            .withMountPath(ossMount.getUserFilesMountPath())
            .withReadOnly(false)
            .withMountPropagation("HostToContainer")
            .build());
        
        // 3. oss-workstation (岗位空间，读写)
        builder.addToVolumeMounts(new VolumeMountBuilder()
            .withName("oss-workstation")
            .withMountPath(ossMount.getWorkstationMountPath())
            .withReadOnly(false)
            .withMountPropagation("HostToContainer")
            .build());
    }
    
    // ==================== 镜像拉取凭证 ====================
    
    private List<LocalObjectReference> buildImagePullSecrets(MergedConfig config) {
        if (StringUtils.hasText(config.getImagePullSecret()) && requiresImagePullSecret(config)) {
            return Collections.singletonList(
                new LocalObjectReference(config.getImagePullSecret())
            );
        }
        return Collections.emptyList();
    }

    private boolean requiresImagePullSecret(MergedConfig config) {
        if (hasRegistryHost(config.getAgentImage())) {
            return true;
        }
        return hasRegistryHost(config.getRunnerImage());
    }

    private boolean hasRegistryHost(String image) {
        if (!StringUtils.hasText(image)) {
            return false;
        }
        String value = image.trim();
        int slash = value.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String first = value.substring(0, slash);
        return first.contains(".") || first.contains(":") || "localhost".equals(first);
    }
    
    // ==================== 节点亲和配置 ====================
    
    private Affinity buildPreferredNodeAffinity(String preferredNode) {
        return new AffinityBuilder()
            .withNewNodeAffinity()
                .addNewPreferredDuringSchedulingIgnoredDuringExecution()
                    .withWeight(100)
                    .withNewPreference()
                        .addNewMatchExpression()
                            .withKey("kubernetes.io/hostname")
                            .withOperator("In")
                            .withValues(preferredNode)
                        .endMatchExpression()
                    .endPreference()
                .endPreferredDuringSchedulingIgnoredDuringExecution()
            .endNodeAffinity()
            .build();
    }
}
