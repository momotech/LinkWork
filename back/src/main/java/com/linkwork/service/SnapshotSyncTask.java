package com.linkwork.service;

import com.linkwork.config.EnvConfig;
import com.linkwork.model.dto.ServiceSnapshot;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Snapshot 与 K8s 状态定期同步任务
 * 
 * 包含两个方向的同步：
 * 1. 正向同步：已有 Snapshot → 从 K8s 校验/更新 Pod 状态
 * 2. 反向发现：扫描 K8s 中运行的服务 → 为缺失 Snapshot 的服务自动重建
 *    （解决后端重启后 Snapshot 丢失导致 scale-down/stop 等操作失败的问题）
 */
@Component
@Slf4j
public class SnapshotSyncTask {
    
    private final ServiceSnapshotService snapshotService;
    private final K8sOrchestrator k8sOrchestrator;
    private final KubernetesClient kubernetesClient;
    private final EnvConfig envConfig;
    
    public SnapshotSyncTask(ServiceSnapshotService snapshotService, 
                           K8sOrchestrator k8sOrchestrator,
                           KubernetesClient kubernetesClient,
                           EnvConfig envConfig) {
        this.snapshotService = snapshotService;
        this.k8sOrchestrator = k8sOrchestrator;
        this.kubernetesClient = kubernetesClient;
        this.envConfig = envConfig;
    }
    
    /**
     * 启动时立即执行一次反向发现，恢复所有 K8s 中运行的服务
     */
    @PostConstruct
    public void onStartup() {
        try {
            log.info("SnapshotSyncTask startup: discovering running services from K8s...");
            int rebuilt = discoverAndRebuildSnapshots();
            if (rebuilt > 0) {
                log.info("Startup discovery complete: rebuilt {} snapshots from K8s", rebuilt);
            } else {
                log.info("Startup discovery complete: no orphan services found in K8s");
            }
        } catch (Exception e) {
            log.error("Startup snapshot discovery failed (non-fatal): {}", e.getMessage(), e);
        }
    }
    
    @Scheduled(fixedRate = 60000)
    public void syncSnapshotWithK8s() {
        // 1. 反向发现：为 K8s 中存在但内存中没有 Snapshot 的服务重建
        try {
            discoverAndRebuildSnapshots();
        } catch (Exception e) {
            log.error("Snapshot reverse discovery failed: {}", e.getMessage());
        }
        
        // 2. 正向同步：已有 Snapshot → 校验 K8s 实际状态
        List<ServiceSnapshot> snapshots = snapshotService.findAllActive();
        
        if (snapshots.isEmpty()) {
            return;
        }
        
        log.debug("Starting snapshot sync task, checking {} active services", snapshots.size());
        
        for (ServiceSnapshot snapshot : snapshots) {
            try {
                syncSingleService(snapshot);
            } catch (Exception e) {
                log.error("Failed to sync snapshot for service {}: {}", 
                    snapshot.getServiceId(), e.getMessage());
            }
        }
    }
    
    /**
     * 反向发现：扫描 K8s namespace 中所有运行的服务，
     * 为没有 Snapshot 的服务从 Pod label/spec 中重建 Snapshot
     * 
     * @return 本次重建的 Snapshot 数量
     */
    private int discoverAndRebuildSnapshots() {
        List<String> allServiceIds = k8sOrchestrator.listAllServiceIds();
        
        if (allServiceIds.isEmpty()) {
            return 0;
        }
        
        int rebuiltCount = 0;
        String namespace = envConfig.getCluster().getNamespace();
        
        for (String serviceId : allServiceIds) {
            if (snapshotService.hasSnapshot(serviceId)) {
                continue;
            }
            
            // 内存中没有这个 Snapshot，需要从 K8s 重建
            try {
                rebuiltCount += rebuildSnapshotForService(serviceId, namespace) ? 1 : 0;
            } catch (Exception e) {
                log.error("Failed to rebuild snapshot for orphan service {}: {}", 
                    serviceId, e.getMessage());
            }
        }
        
        return rebuiltCount;
    }
    
    /**
     * 从 K8s Pod 信息重建单个服务的 Snapshot
     */
    private boolean rebuildSnapshotForService(String serviceId, String namespace) {
        List<Pod> pods = kubernetesClient.pods()
            .inNamespace(namespace)
            .withLabel("service-id", serviceId)
            .list()
            .getItems()
            .stream()
            .filter(this::isReadyPod)
            .collect(Collectors.toList());
        
        if (pods.isEmpty()) {
            log.debug("No running pods for orphan service {}, skip rebuild", serviceId);
            return false;
        }
        
        // 从第一个 Pod 的 label 和 spec 中提取信息
        Pod firstPod = pods.get(0);
        Map<String, String> labels = firstPod.getMetadata().getLabels();
        
        String userId = labels != null ? labels.getOrDefault("user-id", "unknown") : "unknown";
        String podMode = labels != null ? labels.getOrDefault("pod-mode", "sidecar") : "sidecar";
        String scheduledNode = firstPod.getSpec() != null ? firstPod.getSpec().getNodeName() : null;
        
        Set<String> runningPodNames = pods.stream()
            .map(pod -> pod.getMetadata().getName())
            .collect(Collectors.toCollection(HashSet::new));
        
        snapshotService.rebuildFromK8s(serviceId, runningPodNames, userId, podMode, scheduledNode);
        
        log.warn("Rebuilt snapshot for orphan service {} from K8s: pods={}, userId={}, node={}", 
            serviceId, runningPodNames, userId, scheduledNode);
        
        return true;
    }
    
    private void syncSingleService(ServiceSnapshot snapshot) {
        String serviceId = snapshot.getServiceId();
        
        List<String> actualPodNames = k8sOrchestrator.getRunningPods(serviceId);
        int actualCount = actualPodNames.size();
        int snapshotCount = snapshot.getCurrentPodCount() != null ? snapshot.getCurrentPodCount() : 0;
        
        if (actualCount != snapshotCount) {
            log.warn("State inconsistency detected for service {}: snapshot={}, actual={}",
                serviceId, snapshotCount, actualCount);
            
            snapshot.setCurrentPodCount(actualCount);
            snapshot.setRunningPodNames(new HashSet<>(actualPodNames));
            
            if (actualCount == 0 && snapshot.getShutdownAt() == null) {
                snapshot.setShutdownAt(java.time.Instant.now());
                log.info("Service {} has no running pods, marked for expiration in 24 hours", serviceId);
            }
            
            snapshotService.updateSnapshot(snapshot);
            
            log.info("Snapshot synced for service {}: currentPodCount={}, pods={}",
                serviceId, actualCount, actualPodNames);
            return;
        }
        
        Set<String> snapshotPodNames = snapshot.getRunningPodNames();
        if (snapshotPodNames == null) {
            snapshotPodNames = new HashSet<>();
        }
        
        Set<String> actualPodSet = new HashSet<>(actualPodNames);
        if (!snapshotPodNames.equals(actualPodSet)) {
            log.warn("Pod names mismatch for service {}: snapshot={}, actual={}",
                serviceId, snapshotPodNames, actualPodSet);
            
            snapshot.setRunningPodNames(actualPodSet);
            snapshotService.updateSnapshot(snapshot);
            
            log.info("Snapshot pod names synced for service {}: {}", serviceId, actualPodSet);
        }
    }
    
    public void manualSync(String serviceId) {
        if (serviceId != null && !serviceId.isEmpty()) {
            ServiceSnapshot snapshot = snapshotService.getSnapshot(serviceId);
            if (snapshot != null) {
                syncSingleService(snapshot);
            } else {
                // 手动触发时，如果 Snapshot 不存在，尝试从 K8s 重建
                String namespace = envConfig.getCluster().getNamespace();
                rebuildSnapshotForService(serviceId, namespace);
            }
        } else {
            syncSnapshotWithK8s();
        }
    }

    private boolean isReadyPod(Pod pod) {
        if (pod == null || pod.getMetadata() == null || pod.getMetadata().getDeletionTimestamp() != null) {
            return false;
        }
        if (pod.getStatus() == null || pod.getStatus().getPhase() == null) {
            return false;
        }
        if (!"Running".equals(pod.getStatus().getPhase())) {
            return false;
        }
        if (pod.getStatus().getConditions() == null) {
            return false;
        }
        return pod.getStatus().getConditions().stream()
            .anyMatch(condition -> "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
    }
}
