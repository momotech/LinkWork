package com.linkwork.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.EnvConfig;
import com.linkwork.config.ImageBuildConfig;
import com.linkwork.model.dto.*;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * K8s 编排器实现
 */
@Service
@Slf4j
public class K8sOrchestratorImpl implements K8sOrchestrator {

    private static final ResourceSpec FALLBACK_AGENT_RESOURCES = ResourceSpec.builder()
        .cpuRequest("1")
        .cpuLimit("2")
        .memoryRequest("2Gi")
        .memoryLimit("4Gi")
        .build();

    private static final ResourceSpec FALLBACK_RUNNER_RESOURCES = ResourceSpec.builder()
        .cpuRequest("1")
        .cpuLimit("4")
        .memoryRequest("2Gi")
        .memoryLimit("8Gi")
        .build();

    private static final ResourceSpec PERMISSION_INIT_RESOURCES = ResourceSpec.builder()
        .cpuRequest("100m")
        .cpuLimit("500m")
        .memoryRequest("128Mi")
        .memoryLimit("512Mi")
        .build();
    
    private final KubernetesClient kubernetesClient;
    private final PodGroupSpecGenerator podGroupSpecGenerator;
    private final PodSpecGenerator podSpecGenerator;
    private final EnvConfig envConfig;
    private final ObjectMapper objectMapper;
    private final ScheduleEventPublisher eventPublisher;
    private final ImageBuildConfig imageBuildConfig;
    private final DistributedLockService lockService;
    
    /** 存储目录级分布式锁键前缀（保留 oss: 前缀以兼容已有锁） */
    private static final String OSS_LOCK_PREFIX = "oss:lock:";
    
    public K8sOrchestratorImpl(KubernetesClient kubernetesClient,
                               PodGroupSpecGenerator podGroupSpecGenerator,
                               PodSpecGenerator podSpecGenerator,
                               EnvConfig envConfig,
                               ObjectMapper objectMapper,
                               ScheduleEventPublisher eventPublisher,
                               ImageBuildConfig imageBuildConfig,
                               DistributedLockService lockService) {
        this.kubernetesClient = kubernetesClient;
        this.podGroupSpecGenerator = podGroupSpecGenerator;
        this.podSpecGenerator = podSpecGenerator;
        this.envConfig = envConfig;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.imageBuildConfig = imageBuildConfig;
        this.lockService = lockService;
    }
    
    @Override
    public ServiceBuildResult buildService(MergedConfig config) {
        String serviceId = config.getServiceId();
        String podGroupName = "svc-" + serviceId + "-pg";
        
        // 如果启用了 OSS 挂载，获取 OSS 目录级分布式锁
        String ossLockKey = buildOssLockKey(config);
        String ossLock = null;
        if (ossLockKey != null) {
            ossLock = lockService.tryAcquireLockByKey(ossLockKey);
            if (ossLock == null) {
                log.warn("Failed to acquire OSS lock for service {}, key={}", serviceId, ossLockKey);
                return ServiceBuildResult.failed(serviceId, "OSS_LOCK_FAILED",
                    "Another operation is modifying OSS directory for this service");
            }
            log.info("Acquired OSS lock for service {}, key={}", serviceId, ossLockKey);
        }
        
        try {
            return doBuildService(config);
        } finally {
            if (ossLockKey != null && ossLock != null) {
                lockService.releaseLockByKey(ossLockKey, ossLock);
                log.info("Released OSS lock for service {}, key={}", serviceId, ossLockKey);
            }
        }
    }
    
    private ServiceBuildResult doBuildService(MergedConfig config) {
        String serviceId = config.getServiceId();
        String podGroupName = "svc-" + serviceId + "-pg";
        String namespace = config.getNamespace();
        List<String> podNames = new ArrayList<>();
        String scheduledNode = null;
        
        try {
            // ── 重复构建保护：先清理同 serviceId 的旧资源，再重新创建 ──
            List<Pod> existingPods = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel("service-id", serviceId)
                .list()
                .getItems();
            if (!existingPods.isEmpty()) {
                List<String> oldPodNames = existingPods.stream()
                    .map(p -> p.getMetadata().getName())
                    .collect(Collectors.toList());
                log.info("Service {} already has {} pod(s): {}, cleaning up before rebuild",
                    serviceId, existingPods.size(), oldPodNames);
                doCleanupService(serviceId, namespace);
                // 等待旧 Pod 完全删除，防止后续创建时出现 409 冲突
                waitForPodsDeleted(namespace, serviceId, 60);
            }
            
            log.info("Building service {}, podCount={}, podMode={}, namespace={}, preferredNode={}", 
                serviceId, config.getPodCount(), config.getPodMode(), namespace, config.getPreferredNode());
            
            createImagePullSecret(config);
            createTokenSecret(config);
            createAgentConfigMap(config);
            ensureRunnerScriptsConfigMap(config.getNamespace());
            createPodGroup(config);
            waitForPodGroupReady(namespace, podGroupName, 30);
            
            for (int i = 0; i < config.getPodCount(); i++) {
                Pod pod = ensurePodResources(podSpecGenerator.generate(config, i), config);
                Pod createdPod = createPodWithRetry(namespace, pod, 3, 2000);
                String podName = createdPod.getMetadata().getName();
                podNames.add(podName);
                log.info("Created Pod: {}", podName);
                
                eventPublisher.publishPodScheduling(serviceId, podName, i, config.getQueueName());
            }
            
            log.info("Service {} created successfully, pods: {}", serviceId, podNames);
            
            scheduledNode = waitForScheduledNodeWithEvents(namespace, serviceId, podNames, 10);
            log.info("Service {} scheduled to node: {}", serviceId, scheduledNode);
            
            eventPublisher.publishInitComplete(serviceId, podNames.get(0), podGroupName, 
                podNames.size(), config.getPodCount());
            
            return ServiceBuildResult.success(serviceId, podGroupName, podNames, 
                config.getQueueName(), scheduledNode);
            
        } catch (KubernetesClientException e) {
            log.error("Failed to build service {}: {}", serviceId, e.getMessage(), e);
            eventPublisher.publishInitFailed(serviceId, null, podGroupName, "K8S_ERROR", e.getMessage());
            cleanupService(serviceId);
            return ServiceBuildResult.failed(serviceId, "K8S_ERROR", e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error building service {}: {}", serviceId, e.getMessage(), e);
            eventPublisher.publishInitFailed(serviceId, null, podGroupName, "INTERNAL_ERROR", e.getMessage());
            cleanupService(serviceId);
            return ServiceBuildResult.failed(serviceId, "INTERNAL_ERROR", e.getMessage());
        }
    }
    
    private String waitForScheduledNodeWithEvents(String namespace, String serviceId, 
                                                   List<String> podNames, int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;
        
        java.util.Set<String> scheduledPods = new java.util.HashSet<>();
        String firstNodeName = null;
        
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                for (int i = 0; i < podNames.size(); i++) {
                    String podName = podNames.get(i);
                    
                    if (scheduledPods.contains(podName)) {
                        continue;
                    }
                    
                    Pod pod = kubernetesClient.pods()
                        .inNamespace(namespace)
                        .withName(podName)
                        .get();
                    
                    if (pod != null && pod.getSpec() != null && pod.getSpec().getNodeName() != null) {
                        String nodeName = pod.getSpec().getNodeName();
                        
                        eventPublisher.publishPodScheduled(serviceId, podName, i, nodeName);
                        scheduledPods.add(podName);
                        
                        if (firstNodeName == null) {
                            firstNodeName = nodeName;
                        }
                        
                        log.info("Pod {} scheduled to node {}", podName, nodeName);
                    }
                }
                
                if (scheduledPods.size() == podNames.size()) {
                    break;
                }
                
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Error waiting for pods to be scheduled: {}", e.getMessage());
            }
        }
        
        if (scheduledPods.size() < podNames.size()) {
            log.warn("Timeout waiting for pods to be scheduled, scheduled: {}/{}", 
                scheduledPods.size(), podNames.size());
        }
        
        return firstNodeName;
    }
    
    /**
     * 创建镜像拉取 Secret（如果配置了仓库凭证）
     */
    private void createImagePullSecret(MergedConfig config) {
        String secretName = config.getImagePullSecret();
        if (secretName == null || secretName.isEmpty()) {
            return;
        }
        
        String registryHost = resolveRegistryHostForPull(config);
        if (!StringUtils.hasText(registryHost)) {
            log.info("Agent image appears local/no-registry, skipping imagePullSecret creation");
            return;
        }
        String username = imageBuildConfig.getRegistryUsername();
        String password = imageBuildConfig.getRegistryPassword();
        
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            log.info("Registry credentials not configured, skipping imagePullSecret creation");
            return;
        }

        String namespace = config.getNamespace();
        
        // 检查 Secret 是否已存在（存在则覆盖，确保 registry host 与凭证保持最新）
        Secret existing = kubernetesClient.secrets()
            .inNamespace(namespace)
            .withName(secretName)
            .get();
        
        if (existing != null) {
            kubernetesClient.secrets()
                .inNamespace(namespace)
                .withName(secretName)
                .delete();
            log.info("ImagePullSecret {} already exists in namespace {}, recreating", secretName, namespace);
        }
        
        // 构建 dockerconfigjson 格式的凭证
        // 格式: {"auths":{"registry-host":{"username":"user","password":"pass","auth":"base64(user:pass)"}}}
        String auth = java.util.Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        String dockerConfigJson = String.format(
            "{\"auths\":{\"%s\":{\"username\":\"%s\",\"password\":\"%s\",\"auth\":\"%s\"}}}",
            registryHost, username, password, auth
        );
        
        Secret secret = new SecretBuilder()
            .withNewMetadata()
                .withName(secretName)
                .withNamespace(namespace)
            .endMetadata()
            .withType("kubernetes.io/dockerconfigjson")
            .addToData(".dockerconfigjson", 
                java.util.Base64.getEncoder().encodeToString(dockerConfigJson.getBytes()))
            .build();
        
        kubernetesClient.secrets()
            .inNamespace(namespace)
            .resource(secret)
            .create();
        
        log.info("Created ImagePullSecret {} in namespace {}", secretName, namespace);
    }

    private String resolveRegistryHostForPull(MergedConfig config) {
        String registryHostFromImage = extractRegistryHostFromImage(config.getAgentImage());
        if (StringUtils.hasText(registryHostFromImage)) {
            return registryHostFromImage;
        }

        if (StringUtils.hasText(config.getImageRegistry())) {
            return extractRegistryHost(config.getImageRegistry());
        }

        if (StringUtils.hasText(imageBuildConfig.getRegistry())) {
            return extractRegistryHost(imageBuildConfig.getRegistry());
        }

        return "";
    }

    /**
     * 从镜像地址提取 registry host：
     * 仅当首段符合 registry 形式（含 '.'、':' 或 localhost）时认为包含 registry。
     * 例如:
     * - 10.30.107.146/robot/a:b -> 10.30.107.146
     * - docker.io/library/nginx:latest -> docker.io
     * - service-123-agent:tag -> ""
     */
    private String extractRegistryHostFromImage(String image) {
        if (!StringUtils.hasText(image)) {
            return "";
        }
        String value = image.trim();
        int slash = value.indexOf('/');
        if (slash <= 0) {
            return "";
        }
        String first = value.substring(0, slash);
        if (first.contains(".") || first.contains(":") || "localhost".equals(first)) {
            return first;
        }
        return "";
    }

    private String extractRegistryHost(String registry) {
        if (!StringUtils.hasText(registry)) {
            return "";
        }
        String value = registry.trim();
        if (value.startsWith("http://")) {
            value = value.substring("http://".length());
        } else if (value.startsWith("https://")) {
            value = value.substring("https://".length());
        }
        int slash = value.indexOf("/");
        if (slash > 0) {
            return value.substring(0, slash);
        }
        return value;
    }
    
    private void createTokenSecret(MergedConfig config) {
        String secretName = "svc-" + config.getServiceId() + "-token";
        String namespace = config.getNamespace();
        
        Secret existing = kubernetesClient.secrets()
            .inNamespace(namespace)
            .withName(secretName)
            .get();
        
        if (existing != null) {
            log.info("Token Secret {} already exists, updating...", secretName);
            kubernetesClient.secrets()
                .inNamespace(namespace)
                .withName(secretName)
                .delete();
        }
        
        Secret secret = new SecretBuilder()
            .withNewMetadata()
                .withName(secretName)
                .withNamespace(namespace)
                .addToLabels("app", "ai-worker-service")
                .addToLabels("service-id", config.getServiceId())
            .endMetadata()
            .withType("Opaque")
            .addToStringData("token", config.getToken())
            .build();
        
        kubernetesClient.secrets()
            .inNamespace(namespace)
            .resource(secret)
            .create();
        
        log.info("Created Token Secret {} in namespace {}", secretName, namespace);
    }
    
    /**
     * 创建 Agent 配置文件 ConfigMap (per-service)
     * 
     * ConfigMap 名称: svc-{serviceId}-agent-config
     * 数据: config.json → 挂载到 /opt/agent/config.json
     */
    private void createAgentConfigMap(MergedConfig config) {
        String configMapName = PodSpecGenerator.agentConfigMapName(config.getServiceId());
        String namespace = config.getNamespace();
        
        // 删除旧的（如果存在）
        ConfigMap existing = kubernetesClient.configMaps()
            .inNamespace(namespace)
            .withName(configMapName)
            .get();
        if (existing != null) {
            kubernetesClient.configMaps()
                .inNamespace(namespace)
                .withName(configMapName)
                .delete();
            log.info("Deleted existing Agent ConfigMap: {}", configMapName);
        }
        
        // config.json 内容：优先使用 MergedConfig.configJson，否则从 classpath 加载默认配置
        String configJsonContent = config.getConfigJson();
        if (configJsonContent == null || configJsonContent.isBlank()) {
            configJsonContent = loadDefaultAgentConfig();
            log.info("Agent configJson is empty, loaded default from classpath for service {}", config.getServiceId());
        }
        
        ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName(configMapName)
                .withNamespace(namespace)
                .addToLabels("app", "ai-worker-service")
                .addToLabels("service-id", config.getServiceId())
            .endMetadata()
            .addToData("config.json", configJsonContent)
            .build();
        
        kubernetesClient.configMaps()
            .inNamespace(namespace)
            .resource(configMap)
            .create();
        
        log.info("Created Agent ConfigMap {} in namespace {}", configMapName, namespace);
    }
    
    /**
     * 从 classpath 加载默认 Agent config.json
     * 与镜像构建时 COPY 进 /opt/agent/config.json 的内容一致
     */
    private String loadDefaultAgentConfig() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("scripts/config.json")) {
            if (is != null) {
                return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            log.warn("Failed to load default agent config from classpath: {}", e.getMessage());
        }
        log.warn("Default agent config not found in classpath, falling back to '{}'");
        return "{}";
    }
    
    /**
     * 确保 Runner 启动脚本 ConfigMap 最新 (集群级共享，所有 Pod 复用)
     *
     * ConfigMap 名称: runner-start-script
     * 数据: start-runner.sh → 挂载到 /opt/runner/start-runner.sh
     *
     * 每次调用都 createOrReplace，避免旧脚本长期滞留导致权限模型不一致。
     */
    private void ensureRunnerScriptsConfigMap(String namespace) {
        String configMapName = PodSpecGenerator.RUNNER_SCRIPT_CONFIGMAP;

        // 从 classpath 加载 start-runner.sh 内容
        String scriptContent = loadRunnerStartScript();
        
        ConfigMap configMap = new ConfigMapBuilder()
            .withNewMetadata()
                .withName(configMapName)
                .withNamespace(namespace)
                .addToLabels("app", "ai-worker-service")
                .addToLabels("component", "runner")
            .endMetadata()
            .addToData(PodSpecGenerator.RUNNER_SCRIPT_KEY, scriptContent)
            .build();
        
        kubernetesClient.configMaps()
            .inNamespace(namespace)
            .resource(configMap)
            .createOrReplace();

        log.info("Created/Updated Runner scripts ConfigMap {} in namespace {}", configMapName, namespace);
    }
    
    /**
     * 从 classpath 加载 start-runner.sh 脚本内容
     * 路径: classpath:scripts/start-runner.sh
     */
    private String loadRunnerStartScript() {
        try (InputStream is = getClass().getResourceAsStream("/scripts/start-runner.sh")) {
            if (is != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load start-runner.sh from classpath: {}", e.getMessage());
        }
        
        // 兜底：内联最小化的 start-runner.sh
        log.warn("Using fallback inline start-runner.sh");
        return """
            #!/bin/bash
            set -e
            SHARED_KEY_DIR="/shared-keys"
            PUBKEY_FILE="${SHARED_KEY_DIR}/zzd_pubkey.pub"
            PUBKEY_TIMEOUT="${PUBKEY_TIMEOUT:-120}"
            
            echo "[Runner] Starting..."
            
            # 1. sshd 环境
            if [ ! -x /usr/sbin/sshd ]; then
                dnf install -y openssh-server openssh-clients sudo && dnf clean all
            fi
            [ ! -f /etc/ssh/ssh_host_rsa_key ] && ssh-keygen -A
            sed -i 's/^#*PermitRootLogin.*/PermitRootLogin yes/' /etc/ssh/sshd_config
            sed -i 's/^#*PubkeyAuthentication.*/PubkeyAuthentication yes/' /etc/ssh/sshd_config
            sed -i 's/^#*PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
            grep -q '^AuthorizedKeysFile' /etc/ssh/sshd_config || echo 'AuthorizedKeysFile .ssh/authorized_keys' >> /etc/ssh/sshd_config
            
            # 2. momobot 用户
            id momobot &>/dev/null || { groupadd -g 1000 momobot; useradd -u 1000 -g momobot -m -s /bin/bash momobot; echo 'momobot ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/momobot; chmod 0440 /etc/sudoers.d/momobot; }
            
            # 3. 等待公钥
            WAIT=0
            while [ ! -f "$PUBKEY_FILE" ] && [ $WAIT -lt $PUBKEY_TIMEOUT ]; do sleep 1; WAIT=$((WAIT+1)); done
            [ ! -f "$PUBKEY_FILE" ] && { echo "[Runner] ERROR: pubkey timeout"; exit 1; }
            
            # 4. authorized_keys
            mkdir -p /home/momobot/.ssh /root/.ssh
            cp "$PUBKEY_FILE" /home/momobot/.ssh/authorized_keys
            chown -R momobot:momobot /home/momobot/.ssh; chmod 700 /home/momobot/.ssh; chmod 600 /home/momobot/.ssh/authorized_keys
            cp "$PUBKEY_FILE" /root/.ssh/authorized_keys; chmod 700 /root/.ssh; chmod 600 /root/.ssh/authorized_keys
            
            # 5. workspace 权限（共享组模型）
            WORKSPACE_GROUP="${WORKSPACE_GROUP:-workspace}"
            WORKSPACE_GID="${WORKSPACE_GID:-2000}"
            if getent group "${WORKSPACE_GID}" >/dev/null 2>&1; then
              resolved_group=$(getent group "${WORKSPACE_GID}" | cut -d: -f1)
            elif getent group "${WORKSPACE_GROUP}" >/dev/null 2>&1; then
              resolved_group="${WORKSPACE_GROUP}"
            else
              groupadd -g "${WORKSPACE_GID}" "${WORKSPACE_GROUP}"
              resolved_group="${WORKSPACE_GROUP}"
            fi
            usermod -aG "${resolved_group}" momobot
            for dir in /workspace /workspace/logs /workspace/user /workspace/workstation /workspace/task-logs /workspace/worker-logs; do
              mkdir -p "$dir"; chgrp -R "${resolved_group}" "$dir"; chmod -R g+rwX "$dir"; find "$dir" -type d -exec chmod g+s {} +; chmod 2770 "$dir"
            done
            
            # 6. sshd
            exec /usr/sbin/sshd -D -e
            """;
    }
    
    private void createPodGroup(MergedConfig config) {
        Map<String, Object> podGroupSpec = podGroupSpecGenerator.generate(config);
        String namespace = config.getNamespace();
        
        GenericKubernetesResource podGroup = new GenericKubernetesResource();
        podGroup.setApiVersion("scheduling.volcano.sh/v1beta1");
        podGroup.setKind("PodGroup");
        podGroup.setMetadata(new ObjectMetaBuilder()
            .withName("svc-" + config.getServiceId() + "-pg")
            .withNamespace(namespace)
            .addToLabels("app", "ai-worker-service")
            .addToLabels("service-id", config.getServiceId())
            .build());
        podGroup.setAdditionalProperties(Map.of(
            "spec", podGroupSpec.get("spec")
        ));
        
        kubernetesClient.genericKubernetesResources("scheduling.volcano.sh/v1beta1", "PodGroup")
            .inNamespace(namespace)
            .resource(podGroup)
            .createOrReplace();
        
        log.info("Created/Updated PodGroup svc-{}-pg in namespace {}", config.getServiceId(), namespace);
    }
    
    /**
     * 等待 PodGroup phase 离开 Pending 状态。
     * Volcano scheduler 处理 PodGroup 需要时间，如果在 phase=Pending 时就创建 Pod，
     * admission webhook (validatepod.volcano.sh) 会拒绝请求。
     *
     * @param namespace     命名空间
     * @param podGroupName  PodGroup 名称
     * @param timeoutSeconds 最大等待秒数
     */
    private void waitForPodGroupReady(String namespace, String podGroupName, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        long pollIntervalMs = 500;
        String phase = null;
        
        log.info("Waiting for PodGroup {} phase to leave Pending (timeout={}s)...", podGroupName, timeoutSeconds);
        
        while (System.currentTimeMillis() < deadline) {
            try {
                GenericKubernetesResource pg = kubernetesClient
                    .genericKubernetesResources("scheduling.volcano.sh/v1beta1", "PodGroup")
                    .inNamespace(namespace)
                    .withName(podGroupName)
                    .get();
                
                if (pg != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> status = (Map<String, Object>) pg.getAdditionalProperties().get("status");
                    phase = (status != null) ? (String) status.get("phase") : null;
                    
                    if (phase != null && !"Pending".equals(phase)) {
                        log.info("PodGroup {} phase is now '{}', proceeding to create Pods", podGroupName, phase);
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("Error checking PodGroup {} status: {}", podGroupName, e.getMessage());
            }
            
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for PodGroup {}", podGroupName);
                break;
            }
        }
        
        log.warn("PodGroup {} still in phase '{}' after {}s timeout, will attempt Pod creation anyway",
            podGroupName, phase, timeoutSeconds);
    }
    
    /**
     * 等待指定 serviceId 的所有 Pod 完全删除。
     * 防止旧 Pod 处于 Terminating 状态时创建同名新 Pod 导致 409 冲突。
     *
     * @param namespace      命名空间
     * @param serviceId      服务 ID
     * @param timeoutSeconds 最大等待秒数
     */
    private void waitForPodsDeleted(String namespace, String serviceId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        long pollIntervalMs = 1000;
        
        log.info("Waiting for all pods of service {} to be fully deleted (timeout={}s)...", serviceId, timeoutSeconds);
        
        while (System.currentTimeMillis() < deadline) {
            try {
                List<Pod> remaining = kubernetesClient.pods()
                    .inNamespace(namespace)
                    .withLabel("service-id", serviceId)
                    .list()
                    .getItems();
                
                if (remaining.isEmpty()) {
                    log.info("All pods of service {} have been fully deleted", serviceId);
                    return;
                }
                
                List<String> remainingNames = remaining.stream()
                    .map(p -> p.getMetadata().getName())
                    .collect(Collectors.toList());
                log.debug("Still waiting for {} pod(s) to be deleted: {}", remaining.size(), remainingNames);
                
            } catch (Exception e) {
                log.warn("Error checking pod deletion status for service {}: {}", serviceId, e.getMessage());
            }
            
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for pods of service {} to be deleted", serviceId);
                break;
            }
        }
        
        log.warn("Timeout ({}s) waiting for pods of service {} to be fully deleted, proceeding anyway", 
            timeoutSeconds, serviceId);
    }
    
    /**
     * 创建 Pod，带重试机制。
     * 防止 PodGroup 短暂竞态导致 admission webhook 拒绝。
     *
     * @param namespace   命名空间
     * @param pod         Pod 对象
     * @param maxRetries  最大重试次数
     * @param retryDelayMs 重试间隔（毫秒）
     * @return 创建成功的 Pod
     */
    private Pod createPodWithRetry(String namespace, Pod pod, int maxRetries, long retryDelayMs) {
        String podName = pod.getMetadata().getName();
        Exception lastException = null;
        String podDebugJson = toDebugJson(pod);
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.info("Creating Pod {} with spec: {}", podName, podDebugJson);
                return kubernetesClient.pods()
                    .inNamespace(namespace)
                    .resource(pod)
                    .create();
            } catch (Exception e) {
                lastException = e;
                String msg = e.getMessage();
                log.error("Create Pod {} failed on attempt {}/{} with spec: {}", podName, attempt, maxRetries, podDebugJson, e);
                boolean isAdmissionReject = msg != null && msg.contains("podgroup phase is Pending");
                boolean isDeleteConflict = msg != null && msg.contains("object is being deleted");
                
                if ((isAdmissionReject || isDeleteConflict) && attempt < maxRetries) {
                    log.warn("Pod {} creation failed (attempt {}/{}): {}, retrying in {}ms...",
                        podName, attempt, maxRetries, 
                        isDeleteConflict ? "object is being deleted (409 Conflict)" : "admission webhook rejected",
                        retryDelayMs);
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while retrying Pod creation", ie);
                    }
                } else {
                    // 非 admission 竞态错误 或 已达最大重试次数，直接抛出
                    throw new RuntimeException("Failed to create Pod " + podName + " after " + attempt + " attempt(s)", e);
                }
            }
        }
        
        // 不应该到这里，但以防万一
        throw new RuntimeException("Failed to create Pod " + podName + " after " + maxRetries + " retries", lastException);
    }

    private Pod ensurePodResources(Pod pod, MergedConfig config) {
        if (pod == null || pod.getSpec() == null) {
            return pod;
        }

        List<Container> normalizedContainers = new ArrayList<>();
        for (Container container : pod.getSpec().getContainers()) {
            ResourceSpec defaults = "runner".equals(container.getName())
                ? normalizeDefaults(config.getRunnerResources(), FALLBACK_RUNNER_RESOURCES)
                : normalizeDefaults(config.getAgentResources(), FALLBACK_AGENT_RESOURCES);
            normalizedContainers.add(rebuildContainerResources(container, defaults));
        }

        List<Container> initContainers = pod.getSpec().getInitContainers();
        List<Container> normalizedInitContainers = new ArrayList<>();
        if (initContainers != null) {
            for (Container initContainer : initContainers) {
                ResourceSpec defaults = "permission-init".equals(initContainer.getName())
                    ? PERMISSION_INIT_RESOURCES
                    : normalizeDefaults(config.getAgentResources(), FALLBACK_AGENT_RESOURCES);
                normalizedInitContainers.add(rebuildContainerResources(initContainer, defaults));
            }
        }

        Pod normalizedPod = new PodBuilder(pod)
            .editSpec()
                .withContainers(normalizedContainers)
                .withInitContainers(normalizedInitContainers)
            .endSpec()
            .build();
        log.info("Pod {} resource summary before create: {}", pod.getMetadata().getName(), summarizePodResources(normalizedPod));
        return normalizedPod;
    }

    private Container rebuildContainerResources(Container container, ResourceSpec defaults) {
        if (container == null || defaults == null) {
            return container;
        }

        ResourceRequirements current = container.getResources();
        Map<String, Quantity> requests = current != null && current.getRequests() != null
            ? new HashMap<>(current.getRequests())
            : new HashMap<>();
        Map<String, Quantity> limits = current != null && current.getLimits() != null
            ? new HashMap<>(current.getLimits())
            : new HashMap<>();

        putIfMissing(requests, "cpu", defaults.getCpuRequest());
        putIfMissing(limits, "cpu", defaults.getCpuLimit());
        putIfMissing(requests, "memory", defaults.getMemoryRequest());
        putIfMissing(limits, "memory", defaults.getMemoryLimit());

        ResourceRequirements normalized = new ResourceRequirementsBuilder()
            .withRequests(requests)
            .withLimits(limits)
            .build();
        return new ContainerBuilder(container)
            .withResources(normalized)
            .build();
    }

    private boolean putIfMissing(Map<String, Quantity> values, String key, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        Quantity existing = values.get(key);
        if (existing != null && existing.getAmount() != null && !existing.getAmount().isBlank()) {
            return false;
        }
        values.put(key, new Quantity(value));
        return true;
    }

    private ResourceSpec normalizeDefaults(ResourceSpec actual, ResourceSpec fallback) {
        if (fallback == null) {
            return actual;
        }
        if (actual == null) {
            return fallback;
        }
        return ResourceSpec.builder()
            .cpuRequest(isBlank(actual.getCpuRequest()) ? fallback.getCpuRequest() : actual.getCpuRequest())
            .cpuLimit(isBlank(actual.getCpuLimit()) ? fallback.getCpuLimit() : actual.getCpuLimit())
            .memoryRequest(isBlank(actual.getMemoryRequest()) ? fallback.getMemoryRequest() : actual.getMemoryRequest())
            .memoryLimit(isBlank(actual.getMemoryLimit()) ? fallback.getMemoryLimit() : actual.getMemoryLimit())
            .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String summarizePodResources(Pod pod) {
        List<String> parts = new ArrayList<>();
        if (pod.getSpec().getInitContainers() != null) {
            for (Container container : pod.getSpec().getInitContainers()) {
                parts.add("init:" + summarizeContainerResources(container));
            }
        }
        if (pod.getSpec().getContainers() != null) {
            for (Container container : pod.getSpec().getContainers()) {
                parts.add("main:" + summarizeContainerResources(container));
            }
        }
        return String.join("; ", parts);
    }

    private String summarizeContainerResources(Container container) {
        ResourceRequirements resources = container.getResources();
        Map<String, Quantity> requests = resources != null ? resources.getRequests() : null;
        Map<String, Quantity> limits = resources != null ? resources.getLimits() : null;
        return container.getName()
            + "[req.cpu=" + quantityToString(requests, "cpu")
            + ",req.mem=" + quantityToString(requests, "memory")
            + ",lim.cpu=" + quantityToString(limits, "cpu")
            + ",lim.mem=" + quantityToString(limits, "memory") + "]";
    }

    private String quantityToString(Map<String, Quantity> values, String key) {
        if (values == null) {
            return "null";
        }
        Quantity quantity = values.get(key);
        return quantity == null ? "null" : quantity.toString();
    }

    private String toDebugJson(Pod pod) {
        try {
            return objectMapper.writeValueAsString(pod);
        } catch (Exception e) {
            return "{\"error\":\"failed to serialize pod\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
    
    @Override
    public ServiceStatusResponse getServiceStatus(String serviceId) {
        String namespace = envConfig.getCluster().getNamespace();
        
        PodGroupStatusInfo podGroupStatus = getPodGroupStatus(serviceId, namespace);
        List<PodStatusInfo> podStatuses = getPodStatuses(serviceId, namespace);
        enrichWithMetrics(podStatuses, serviceId, namespace);
        
        return ServiceStatusResponse.builder()
            .serviceId(serviceId)
            .podGroupStatus(podGroupStatus)
            .pods(podStatuses)
            .updatedAt(Instant.now())
            .build();
    }
    
    private void enrichWithMetrics(List<PodStatusInfo> podStatuses, String serviceId, String namespace) {
        try {
            Map<String, PodMetrics> metricsMap = getPodMetrics(serviceId, namespace);
            log.info("Found {} metrics for service {}", metricsMap.size(), serviceId);
            
            for (PodStatusInfo podStatus : podStatuses) {
                PodMetrics metrics = metricsMap.get(podStatus.getName());
                if (metrics != null) {
                    log.info("Enriching metrics for pod {}", podStatus.getName());
                    podStatus.setResourceUsage(aggregatePodMetrics(metrics, podStatus));
                    enrichContainerMetrics(podStatus, metrics);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get metrics for service {}: {}", serviceId, e.getMessage(), e);
        }
    }
    
    private Map<String, PodMetrics> getPodMetrics(String serviceId, String namespace) {
        Map<String, PodMetrics> metricsMap = new HashMap<>();
        
        try {
            List<PodMetrics> allMetrics = kubernetesClient.top()
                .pods()
                .inNamespace(namespace)
                .metrics()
                .getItems();
            
            for (PodMetrics pm : allMetrics) {
                Map<String, String> labels = pm.getMetadata().getLabels();
                if (labels != null && serviceId.equals(labels.get("service-id"))) {
                    metricsMap.put(pm.getMetadata().getName(), pm);
                }
            }
        } catch (Exception e) {
            log.warn("Metrics not available: {}", e.getMessage());
        }
        
        return metricsMap;
    }
    
    private ResourceUsageInfo aggregatePodMetrics(PodMetrics metrics, PodStatusInfo podStatus) {
        long totalCpuMillicores = 0;
        long totalMemoryBytes = 0;
        
        for (ContainerMetrics cm : metrics.getContainers()) {
            Quantity cpuQuantity = cm.getUsage().get("cpu");
            Quantity memQuantity = cm.getUsage().get("memory");
            
            if (cpuQuantity != null) {
                totalCpuMillicores += parseMillicores(cpuQuantity.getAmount(), cpuQuantity.getFormat());
            }
            if (memQuantity != null) {
                totalMemoryBytes += parseBytes(memQuantity.getAmount(), memQuantity.getFormat());
            }
        }
        
        String cpuLimit = null;
        String memoryLimit = null;
        String cpuRequest = null;
        String memoryRequest = null;
        
        if (podStatus.getContainers() != null && !podStatus.getContainers().isEmpty()) {
            ContainerStatusInfo firstContainer = podStatus.getContainers().get(0);
            if (firstContainer.getResourceUsage() != null) {
                cpuLimit = firstContainer.getResourceUsage().getCpuLimit();
                memoryLimit = firstContainer.getResourceUsage().getMemoryLimit();
                cpuRequest = firstContainer.getResourceUsage().getCpuRequest();
                memoryRequest = firstContainer.getResourceUsage().getMemoryRequest();
            }
        }
        
        return ResourceUsageInfo.builder()
            .cpuUsage(formatMillicores(totalCpuMillicores))
            .memoryUsage(formatBytes(totalMemoryBytes))
            .cpuMillicores(totalCpuMillicores)
            .memoryBytes(totalMemoryBytes)
            .cpuLimit(cpuLimit)
            .memoryLimit(memoryLimit)
            .cpuRequest(cpuRequest)
            .memoryRequest(memoryRequest)
            .cpuUsagePercent(calculateUsagePercent(totalCpuMillicores, cpuLimit))
            .memoryUsagePercent(calculateUsagePercent(totalMemoryBytes, memoryLimit))
            .build();
    }
    
    private void enrichContainerMetrics(PodStatusInfo podStatus, PodMetrics metrics) {
        if (podStatus.getContainers() == null) return;
        
        Map<String, ContainerMetrics> containerMetricsMap = new HashMap<>();
        for (ContainerMetrics cm : metrics.getContainers()) {
            containerMetricsMap.put(cm.getName(), cm);
        }
        
        for (ContainerStatusInfo containerStatus : podStatus.getContainers()) {
            ContainerMetrics cm = containerMetricsMap.get(containerStatus.getName());
            if (cm != null) {
                Quantity cpuQuantity = cm.getUsage().get("cpu");
                Quantity memQuantity = cm.getUsage().get("memory");
                
                long cpuMillicores = cpuQuantity != null 
                    ? parseMillicores(cpuQuantity.getAmount(), cpuQuantity.getFormat()) : 0;
                long memoryBytes = memQuantity != null 
                    ? parseBytes(memQuantity.getAmount(), memQuantity.getFormat()) : 0;
                
                ResourceUsageInfo existingUsage = containerStatus.getResourceUsage();
                String cpuLimit = existingUsage != null ? existingUsage.getCpuLimit() : null;
                String memoryLimit = existingUsage != null ? existingUsage.getMemoryLimit() : null;
                
                containerStatus.setResourceUsage(ResourceUsageInfo.builder()
                    .cpuUsage(formatMillicores(cpuMillicores))
                    .memoryUsage(formatBytes(memoryBytes))
                    .cpuMillicores(cpuMillicores)
                    .memoryBytes(memoryBytes)
                    .cpuLimit(cpuLimit)
                    .memoryLimit(memoryLimit)
                    .cpuRequest(existingUsage != null ? existingUsage.getCpuRequest() : null)
                    .memoryRequest(existingUsage != null ? existingUsage.getMemoryRequest() : null)
                    .cpuUsagePercent(calculateUsagePercent(cpuMillicores, cpuLimit))
                    .memoryUsagePercent(calculateUsagePercent(memoryBytes, memoryLimit))
                    .build());
            }
        }
    }
    
    private long parseMillicores(String amount, String format) {
        try {
            if (amount == null) return 0;
            double value = Double.parseDouble(amount);
            
            if (format != null) {
                if (format.equals("n")) {
                    return (long) (value / 1_000_000);
                } else if (format.equals("u")) {
                    return (long) (value / 1_000);
                } else if (format.equals("m")) {
                    return (long) value;
                }
            }
            
            return (long) (value * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    private long parseBytes(String amount, String format) {
        try {
            if (amount == null) return 0;
            double value = Double.parseDouble(amount);
            
            if (format != null) {
                switch (format) {
                    case "Ki": return (long) (value * 1024);
                    case "Mi": return (long) (value * 1024 * 1024);
                    case "Gi": return (long) (value * 1024 * 1024 * 1024);
                    case "K": return (long) (value * 1000);
                    case "M": return (long) (value * 1000 * 1000);
                    case "G": return (long) (value * 1000 * 1000 * 1000);
                }
            }
            
            return (long) value;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    private String formatMillicores(long millicores) {
        if (millicores >= 1000) {
            return String.format("%.2f", millicores / 1000.0);
        }
        return millicores + "m";
    }
    
    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.2fGi", bytes / (1024.0 * 1024 * 1024));
        } else if (bytes >= 1024L * 1024) {
            return String.format("%.2fMi", bytes / (1024.0 * 1024));
        } else if (bytes >= 1024) {
            return String.format("%.2fKi", bytes / 1024.0);
        }
        return bytes + "B";
    }
    
    private Double calculateUsagePercent(long usage, String limit) {
        if (limit == null || limit.isEmpty()) return null;
        
        try {
            long limitValue;
            if (limit.endsWith("m")) {
                limitValue = Long.parseLong(limit.substring(0, limit.length() - 1));
            } else if (limit.endsWith("Gi")) {
                limitValue = (long) (Double.parseDouble(limit.substring(0, limit.length() - 2)) * 1024 * 1024 * 1024);
            } else if (limit.endsWith("Mi")) {
                limitValue = (long) (Double.parseDouble(limit.substring(0, limit.length() - 2)) * 1024 * 1024);
            } else {
                limitValue = Long.parseLong(limit) * 1000;
            }
            
            if (limitValue == 0) return null;
            return Math.round(usage * 10000.0 / limitValue) / 100.0;
        } catch (Exception e) {
            return null;
        }
    }
    
    private PodGroupStatusInfo getPodGroupStatus(String serviceId, String namespace) {
        String podGroupName = "svc-" + serviceId + "-pg";
        
        try {
            GenericKubernetesResource podGroup = kubernetesClient
                .genericKubernetesResources("scheduling.volcano.sh/v1beta1", "PodGroup")
                .inNamespace(namespace)
                .withName(podGroupName)
                .get();
            
            if (podGroup == null) {
                return null;
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> status = (Map<String, Object>) podGroup.getAdditionalProperties().get("status");
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = (Map<String, Object>) podGroup.getAdditionalProperties().get("spec");
            
            return PodGroupStatusInfo.builder()
                .name(podGroupName)
                .phase(status != null ? (String) status.get("phase") : "Unknown")
                .minMember(spec != null ? (Integer) spec.get("minMember") : 1)
                .running(status != null ? toInt(status.get("running")) : 0)
                .succeeded(status != null ? toInt(status.get("succeeded")) : 0)
                .failed(status != null ? toInt(status.get("failed")) : 0)
                .pending(status != null ? toInt(status.get("pending")) : 0)
                .build();
        } catch (Exception e) {
            log.warn("Failed to get PodGroup status: {}", e.getMessage());
            return null;
        }
    }
    
    private List<PodStatusInfo> getPodStatuses(String serviceId, String namespace) {
        List<Pod> pods = kubernetesClient.pods()
            .inNamespace(namespace)
            .withLabel("service-id", serviceId)
            .list()
            .getItems();
        
        return pods.stream()
            .map(this::toPodStatusInfo)
            .collect(Collectors.toList());
    }
    
    private PodStatusInfo toPodStatusInfo(Pod pod) {
        PodStatus status = pod.getStatus();
        PodSpec spec = pod.getSpec();
        
        Map<String, Container> containerSpecMap = new HashMap<>();
        if (spec != null && spec.getContainers() != null) {
            for (Container c : spec.getContainers()) {
                containerSpecMap.put(c.getName(), c);
            }
        }
        
        List<ContainerStatusInfo> containerStatuses = new ArrayList<>();
        if (status != null && status.getContainerStatuses() != null) {
            for (ContainerStatus cs : status.getContainerStatuses()) {
                Container containerSpec = containerSpecMap.get(cs.getName());
                ResourceUsageInfo resourceUsage = getContainerResourceLimits(containerSpec);
                
                containerStatuses.add(ContainerStatusInfo.builder()
                    .name(cs.getName())
                    .ready(cs.getReady() != null && cs.getReady())
                    .state(getContainerState(cs))
                    .reason(getContainerReason(cs))
                    .exitCode(getContainerExitCode(cs))
                    .restartCount(cs.getRestartCount())
                    .resourceUsage(resourceUsage)
                    .build());
            }
        }
        
        String nodeHostname = spec != null ? spec.getNodeName() : null;
        
        return PodStatusInfo.builder()
            .name(pod.getMetadata().getName())
            .phase(status != null ? status.getPhase() : "Unknown")
            .nodeName(status != null ? status.getHostIP() : null)
            .nodeHostname(nodeHostname)
            .startTime(status != null && status.getStartTime() != null 
                ? Instant.parse(status.getStartTime()) : null)
            .containers(containerStatuses)
            .build();
    }
    
    private ResourceUsageInfo getContainerResourceLimits(Container container) {
        if (container == null || container.getResources() == null) {
            return null;
        }
        
        ResourceRequirements resources = container.getResources();
        Map<String, Quantity> limits = resources.getLimits();
        Map<String, Quantity> requests = resources.getRequests();
        
        return ResourceUsageInfo.builder()
            .cpuLimit(limits != null && limits.get("cpu") != null 
                ? limits.get("cpu").getAmount() + (limits.get("cpu").getFormat() != null ? limits.get("cpu").getFormat() : "") : null)
            .memoryLimit(limits != null && limits.get("memory") != null 
                ? limits.get("memory").getAmount() + (limits.get("memory").getFormat() != null ? limits.get("memory").getFormat() : "") : null)
            .cpuRequest(requests != null && requests.get("cpu") != null 
                ? requests.get("cpu").getAmount() + (requests.get("cpu").getFormat() != null ? requests.get("cpu").getFormat() : "") : null)
            .memoryRequest(requests != null && requests.get("memory") != null 
                ? requests.get("memory").getAmount() + (requests.get("memory").getFormat() != null ? requests.get("memory").getFormat() : "") : null)
            .build();
    }
    
    private String getContainerState(ContainerStatus cs) {
        if (cs.getState() == null) return "unknown";
        if (cs.getState().getRunning() != null) return "running";
        if (cs.getState().getWaiting() != null) return "waiting";
        if (cs.getState().getTerminated() != null) return "terminated";
        return "unknown";
    }
    
    private String getContainerReason(ContainerStatus cs) {
        if (cs.getState() == null) return null;
        if (cs.getState().getWaiting() != null) return cs.getState().getWaiting().getReason();
        if (cs.getState().getTerminated() != null) return cs.getState().getTerminated().getReason();
        return null;
    }
    
    private Integer getContainerExitCode(ContainerStatus cs) {
        if (cs.getState() == null) return null;
        if (cs.getState().getTerminated() != null) return cs.getState().getTerminated().getExitCode();
        return null;
    }
    
    @Override
    public StopResult stopService(String serviceId, boolean graceful) {
        String namespace = envConfig.getCluster().getNamespace();
        
        // 如果启用了 OSS 挂载，通过 Pod label 获取 userId 并加锁
        String ossLockKey = buildOssLockKeyFromPods(serviceId, namespace);
        String ossLock = null;
        if (ossLockKey != null) {
            ossLock = lockService.tryAcquireLockByKey(ossLockKey);
            if (ossLock == null) {
                log.warn("Failed to acquire OSS lock for stop service {}, key={}", serviceId, ossLockKey);
                // 停止操作不应因锁失败而完全阻塞，记录警告后继续
                log.warn("Proceeding with stop without OSS lock for service {}", serviceId);
            } else {
                log.info("Acquired OSS lock for stop service {}, key={}", serviceId, ossLockKey);
            }
        }
        
        try {
            return doStopService(serviceId, graceful, namespace);
        } finally {
            if (ossLockKey != null && ossLock != null) {
                lockService.releaseLockByKey(ossLockKey, ossLock);
                log.info("Released OSS lock for stop service {}, key={}", serviceId, ossLockKey);
            }
        }
    }
    
    private StopResult doStopService(String serviceId, boolean graceful, String namespace) {
        String podGroupName = "svc-" + serviceId + "-pg";
        
        try {
            log.info("Stopping service {}, graceful={}", serviceId, graceful);
            
            List<Pod> pods = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel("service-id", serviceId)
                .list()
                .getItems();
            int podCount = pods.size();
            
            long gracePeriod = graceful ? 30L : 0L;
            
            kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel("service-id", serviceId)
                .withGracePeriod(gracePeriod)
                .delete();
            
            try {
                kubernetesClient.genericKubernetesResources("scheduling.volcano.sh/v1beta1", "PodGroup")
                    .inNamespace(namespace)
                    .withName(podGroupName)
                    .delete();
            } catch (Exception e) {
                log.warn("Failed to delete PodGroup: {}", e.getMessage());
            }
            
            try {
                String secretName = "svc-" + serviceId + "-token";
                kubernetesClient.secrets()
                    .inNamespace(namespace)
                    .withName(secretName)
                    .delete();
                log.info("Deleted Token Secret: {}", secretName);
            } catch (Exception e) {
                log.warn("Failed to delete Token Secret: {}", e.getMessage());
            }
            
            eventPublisher.publishSessionEnd(serviceId, podGroupName, podCount, graceful);
            
            return StopResult.success(serviceId);
            
        } catch (Exception e) {
            log.error("Failed to stop service {}: {}", serviceId, e.getMessage());
            return StopResult.failed(serviceId, e.getMessage());
        }
    }
    
    @Override
    public void cleanupService(String serviceId) {
        String namespace = envConfig.getCluster().getNamespace();
        
        // 如果启用了 OSS 挂载，尝试加锁（cleanup 不阻塞）
        String ossLockKey = buildOssLockKeyFromPods(serviceId, namespace);
        String ossLock = null;
        if (ossLockKey != null) {
            ossLock = lockService.tryAcquireLockByKey(ossLockKey);
            if (ossLock != null) {
                log.info("Acquired OSS lock for cleanup service {}, key={}", serviceId, ossLockKey);
            }
        }
        
        try {
            doCleanupService(serviceId, namespace);
        } finally {
            if (ossLockKey != null && ossLock != null) {
                lockService.releaseLockByKey(ossLockKey, ossLock);
                log.info("Released OSS lock for cleanup service {}, key={}", serviceId, ossLockKey);
            }
        }
    }
    
    private void doCleanupService(String serviceId, String namespace) {
        log.info("Cleaning up resources for service {} in namespace {}", serviceId, namespace);
        
        try {
            kubernetesClient.genericKubernetesResources("scheduling.volcano.sh/v1beta1", "PodGroup")
                .inNamespace(namespace)
                .withName("svc-" + serviceId + "-pg")
                .delete();
        } catch (Exception e) {
            log.warn("Failed to delete PodGroup: {}", e.getMessage());
        }
        
        kubernetesClient.pods()
            .inNamespace(namespace)
            .withLabel("service-id", serviceId)
            .delete();
        
        kubernetesClient.secrets()
            .inNamespace(namespace)
            .withLabel("service-id", serviceId)
            .delete();
        
        kubernetesClient.configMaps()
            .inNamespace(namespace)
            .withLabel("service-id", serviceId)
            .delete();
        
        log.info("Cleaned up all resources for service {}", serviceId);
    }
    
    // ==================== OSS 锁辅助方法 ====================
    
    /**
     * 从 MergedConfig 构建 OSS 锁键
     * 格式: oss:lock:{serviceId}  (精确到 workstationId 级别)
     * 
     * @return 锁键，如果 OSS 未启用返回 null
     */
    private String buildOssLockKey(MergedConfig config) {
        if (config.getOssMount() == null || !config.getOssMount().isEnabled()) {
            return null;
        }
        return OSS_LOCK_PREFIX + config.getServiceId();
    }
    
    /**
     * 从 serviceId 构建 OSS 锁键
     * 用于 stopService/cleanupService 等不传 MergedConfig 的场景
     * 
     * @return 锁键，如果 OSS 未启用返回 null
     */
    private String buildOssLockKeyFromPods(String serviceId, String namespace) {
        if (!envConfig.getOssMount().isEnabled()) {
            return null;
        }
        return OSS_LOCK_PREFIX + serviceId;
    }
    
    @Override
    public GeneratedSpec previewSpec(MergedConfig config) {
        Map<String, Object> podGroupSpec = podGroupSpecGenerator.generate(config);
        
        List<Map<String, Object>> podSpecs = new ArrayList<>();
        for (int i = 0; i < config.getPodCount(); i++) {
            Pod pod = podSpecGenerator.generate(config, i);
            Map<String, Object> podMap = convertPodToMap(pod);
            podSpecs.add(podMap);
        }
        
        return GeneratedSpec.builder()
            .serviceId(config.getServiceId())
            .podGroupSpec(podGroupSpec)
            .podSpecs(podSpecs)
            .build();
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertPodToMap(Pod pod) {
        try {
            String json = objectMapper.writeValueAsString(pod);
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            log.error("Failed to convert Pod to Map: {}", e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
    
    private int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Number) return ((Number) value).intValue();
        return 0;
    }
    
    @Override
    public List<String> getRunningPods(String serviceId) {
        String namespace = envConfig.getCluster().getNamespace();
        
        List<Pod> pods = kubernetesClient.pods()
            .inNamespace(namespace)
            .withLabel("service-id", serviceId)
            .list()
            .getItems();
        
        return pods.stream()
            .filter(this::isReadyPod)
            .map(pod -> pod.getMetadata().getName())
            .sorted()
            .collect(Collectors.toList());
    }
    
    @Override
    public List<String> listAllServiceIds() {
        String namespace = envConfig.getCluster().getNamespace();
        
        try {
            List<Pod> pods = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel("app", "ai-worker-service")
                .list()
                .getItems();
            
            return pods.stream()
                .filter(this::isReadyPod)
                .map(pod -> pod.getMetadata().getLabels().get("service-id"))
                .filter(id -> id != null && !id.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            log.error("Failed to list all service IDs from K8s: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private boolean isReadyPod(Pod pod) {
        if (pod == null || pod.getMetadata() == null || pod.getMetadata().getDeletionTimestamp() != null) {
            return false;
        }
        if (pod.getStatus() == null || pod.getStatus().getPhase() == null) {
            return false;
        }

        String phase = pod.getStatus().getPhase();
        if (!"Running".equals(phase)) {
            return false;
        }

        List<PodCondition> conditions = pod.getStatus().getConditions();
        if (conditions == null) {
            return false;
        }

        return conditions.stream()
            .anyMatch(condition -> "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
    }
    
    @Override
    public ScaleResult scaleDown(String serviceId, String podName) {
        String namespace = envConfig.getCluster().getNamespace();
        
        try {
            List<String> runningPods = getRunningPods(serviceId);
            int previousCount = runningPods.size();
            
            if (previousCount == 0) {
                log.warn("No running pods found for service {}", serviceId);
                return ScaleResult.failed(serviceId, "No running pods to scale down");
            }
            
            if (podName == null || podName.isEmpty()) {
                return ScaleResult.failed(serviceId, "podName is required for scale-down");
            }
            
            String targetPod = podName;
            if (!runningPods.contains(targetPod)) {
                return ScaleResult.failed(serviceId, "Pod not found: " + targetPod);
            }
            
            log.info("Scaling down service {}: deleting pod {}", serviceId, targetPod);
            
            kubernetesClient.pods()
                .inNamespace(namespace)
                .withName(targetPod)
                .withGracePeriod(0L)
                .delete();
            
            runningPods.remove(targetPod);
            
            log.info("Service {} scaled down: {} -> {} pods", 
                serviceId, previousCount, runningPods.size());
            
            return ScaleResult.success(
                serviceId, 
                "SCALE_DOWN",
                previousCount,
                runningPods.size(),
                previousCount,
                runningPods,
                null,
                List.of(targetPod)
            );
            
        } catch (Exception e) {
            log.error("Failed to scale down service {}: {}", serviceId, e.getMessage(), e);
            return ScaleResult.failed(serviceId, e.getMessage());
        }
    }
    
    @Override
    public ScaleResult scaleUp(String serviceId, int targetPodCount, MergedConfig config) {
        String namespace = envConfig.getCluster().getNamespace();
        
        try {
            cleanupTerminatedPods(serviceId, namespace);
            
            List<String> runningPods = getRunningPods(serviceId);
            int previousCount = runningPods.size();

            // 当服务当前无存活 Pod 时，先刷新依赖资源，避免使用到过期 ConfigMap/Secret。
            if (previousCount == 0) {
                createImagePullSecret(config);
                createTokenSecret(config);
                createAgentConfigMap(config);
                ensureRunnerScriptsConfigMap(config.getNamespace());
                createPodGroup(config);
            }
            
            if (targetPodCount <= previousCount) {
                log.info("Service {} already has {} pods, target is {}, no scaling needed",
                    serviceId, previousCount, targetPodCount);
                return ScaleResult.noChange(serviceId, previousCount, targetPodCount, runningPods);
            }
            
            int podsToCreate = targetPodCount - previousCount;
            
            log.info("Scaling up service {}: {} -> {} pods (creating {} new pods)",
                serviceId, previousCount, targetPodCount, podsToCreate);
            
            List<Integer> existingIndices = runningPods.stream()
                .map(name -> {
                    String[] parts = name.split("-");
                    try {
                        return Integer.parseInt(parts[parts.length - 1]);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                })
                .filter(i -> i >= 0)
                .collect(Collectors.toList());
            
            List<String> addedPods = new ArrayList<>();
            int nextIndex = existingIndices.isEmpty() ? 0 : existingIndices.stream().max(Integer::compare).orElse(0) + 1;
            
            for (int i = 0; i < podsToCreate; i++) {
                while (existingIndices.contains(nextIndex)) {
                    nextIndex++;
                }
                
                Pod pod = podSpecGenerator.generate(config, nextIndex);
                Pod createdPod = kubernetesClient.pods()
                    .inNamespace(namespace)
                    .resource(pod)
                    .create();
                
                String podName = createdPod.getMetadata().getName();
                addedPods.add(podName);
                runningPods.add(podName);
                existingIndices.add(nextIndex);
                
                eventPublisher.publishPodScheduling(serviceId, podName, nextIndex, config.getQueueName());
                
                log.info("Created Pod: {} (index={})", podName, nextIndex);
                nextIndex++;
            }
            
            waitForScheduledNodeWithEvents(namespace, serviceId, addedPods, 10);
            
            log.info("Service {} scaled up: {} -> {} pods, added: {}", 
                serviceId, previousCount, runningPods.size(), addedPods);
            
            return ScaleResult.success(
                serviceId,
                "SCALE_UP",
                previousCount,
                runningPods.size(),
                targetPodCount,
                runningPods,
                addedPods,
                null
            );
            
        } catch (Exception e) {
            log.error("Failed to scale up service {}: {}", serviceId, e.getMessage(), e);
            return ScaleResult.failed(serviceId, e.getMessage());
        }
    }
    
    /**
     * 清理已终止的 Pod（Succeeded/Failed），防止 scaleUp 创建同名 Pod 时 409 冲突。
     */
    private void cleanupTerminatedPods(String serviceId, String namespace) {
        try {
            List<Pod> allPods = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel("service-id", serviceId)
                .list()
                .getItems();
            
            List<String> terminatedPodNames = allPods.stream()
                .filter(pod -> {
                    String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : null;
                    return "Succeeded".equals(phase) || "Failed".equals(phase);
                })
                .map(pod -> pod.getMetadata().getName())
                .collect(Collectors.toList());
            
            if (terminatedPodNames.isEmpty()) {
                return;
            }
            
            log.info("Cleaning up {} terminated pod(s) for service {}: {}",
                terminatedPodNames.size(), serviceId, terminatedPodNames);
            
            for (String podName : terminatedPodNames) {
                kubernetesClient.pods()
                    .inNamespace(namespace)
                    .withName(podName)
                    .withGracePeriod(0L)
                    .delete();
            }
            
            waitForPodsDeleted(namespace, serviceId, 30);
        } catch (Exception e) {
            log.warn("Failed to cleanup terminated pods for service {}: {}", serviceId, e.getMessage());
        }
    }
}
