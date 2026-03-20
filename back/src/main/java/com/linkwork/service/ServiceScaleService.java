package com.linkwork.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.model.dto.*;
import com.linkwork.model.entity.BuildRecordEntity;
import com.linkwork.model.enums.DeployMode;
import com.linkwork.model.enums.PodMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 服务动态伸缩服务
 */
@Service
@Slf4j
public class ServiceScaleService {
    
    private final K8sOrchestrator k8sOrchestrator;
    private final ServiceSnapshotService snapshotService;
    private final ConfigMergeService configMergeService;
    private final DistributedLockService lockService;
    private final BuildRecordService buildRecordService;
    private final ObjectMapper objectMapper;
    
    private static final int SCALE_UP_MAX_RETRIES = 3;
    
    public ServiceScaleService(K8sOrchestrator k8sOrchestrator,
                               ServiceSnapshotService snapshotService,
                               ConfigMergeService configMergeService,
                               DistributedLockService lockService,
                               BuildRecordService buildRecordService,
                               ObjectMapper objectMapper) {
        this.k8sOrchestrator = k8sOrchestrator;
        this.snapshotService = snapshotService;
        this.configMergeService = configMergeService;
        this.lockService = lockService;
        this.buildRecordService = buildRecordService;
        this.objectMapper = objectMapper;
    }
    
    public ScaleResult scaleDown(String serviceId, String podName, String source) {
        log.info("Scale down request for service {}, podName={}, source={}", 
            serviceId, podName, source);
        
        String lockValue = lockService.tryAcquireLock(serviceId);
        if (lockValue == null) {
            return ScaleResult.failed(serviceId, 
                "Failed to acquire lock, another scaling operation may be in progress");
        }
        
        try {
            return doScaleDown(serviceId, podName);
        } finally {
            lockService.releaseLock(serviceId, lockValue);
        }
    }
    
    private ScaleResult doScaleDown(String serviceId, String podName) {
        ServiceSnapshot snapshot = snapshotService.getSnapshot(serviceId);
        if (snapshot == null) {
            return ScaleResult.failed(serviceId, "Snapshot not found for service");
        }
        
        int previousCount = snapshot.getCurrentPodCount() != null ? snapshot.getCurrentPodCount() : 0;
        int maxPodCount = snapshot.getMaxPodCount() != null ? snapshot.getMaxPodCount() : previousCount;
        
        ScaleResult k8sResult = k8sOrchestrator.scaleDown(serviceId, podName);
        if (!k8sResult.isSuccess()) {
            log.error("Failed to delete pod {} for service {}: {}", 
                podName, serviceId, k8sResult.getErrorMessage());
            return k8sResult;
        }
        
        int currentCount = k8sResult.getCurrentPodCount();
        snapshot.setCurrentPodCount(currentCount);
        
        if (snapshot.getRunningPodNames() != null) {
            snapshot.getRunningPodNames().remove(podName);
        }
        
        snapshotService.updateSnapshot(snapshot);
        
        log.info("Scale down result for service {}: {} -> {} pods",
            serviceId, previousCount, currentCount);
        
        return ScaleResult.success(
            serviceId,
            "SCALE_DOWN",
            previousCount,
            currentCount,
            maxPodCount,
            k8sResult.getRunningPods(),
            null,
            List.of(podName)
        );
    }
    
    public ScaleResult scaleUp(String serviceId, Integer targetPodCount, String source) {
        log.info("Scale up request for service {}, targetPodCount={}, source={}",
            serviceId, targetPodCount, source);
        
        String lockValue = lockService.tryAcquireLock(serviceId);
        if (lockValue == null) {
            return ScaleResult.failed(serviceId, 
                "Failed to acquire lock, another scaling operation may be in progress");
        }
        
        try {
            return doScaleUp(serviceId, targetPodCount);
        } finally {
            lockService.releaseLock(serviceId, lockValue);
        }
    }
    
    private ScaleResult doScaleUp(String serviceId, Integer targetPodCount) {
        ServiceSnapshot snapshot = snapshotService.getSnapshot(serviceId);
        if (snapshot == null) {
            snapshot = tryRecoverSnapshotFromSources(serviceId);
        }
        if (snapshot == null) {
            return ScaleResult.failed(serviceId, "Snapshot not found, cannot scale up");
        }
        
        int maxPodCount = snapshot.getMaxPodCount() != null ? snapshot.getMaxPodCount() : 1;
        int target = targetPodCount != null ? targetPodCount : maxPodCount;
        int currentCount = snapshot.getCurrentPodCount() != null ? snapshot.getCurrentPodCount() : 0;
        
        if (target > maxPodCount) {
            log.warn("Target pod count {} exceeds max {}, using max", target, maxPodCount);
            target = maxPodCount;
        }
        
        if (currentCount >= target) {
            List<String> runningPods = k8sOrchestrator.getRunningPods(serviceId);
            return ScaleResult.noChange(serviceId, currentCount, maxPodCount, runningPods);
        }
        
        MergedConfig config = restoreConfig(snapshot);
        if (config == null) {
            return ScaleResult.failed(serviceId, "Failed to restore config from snapshot");
        }
        
        if (snapshot.getLastScheduledNode() != null) {
            config.setPreferredNode(snapshot.getLastScheduledNode());
        }
        
        int toAdd = target - currentCount;
        List<String> addedPods = new ArrayList<>();
        
        for (int i = 0; i < toAdd; i++) {
            String newPodName = createPodWithRetry(serviceId, config, snapshot);
            if (newPodName != null) {
                addedPods.add(newPodName);
                
                if (snapshot.getRunningPodNames() == null) {
                    snapshot.setRunningPodNames(new HashSet<>());
                }
                snapshot.getRunningPodNames().add(newPodName);
            } else {
                log.warn("Failed to create pod after {} retries, stopping scale-up", SCALE_UP_MAX_RETRIES);
                break;
            }
        }
        
        int newCount = currentCount + addedPods.size();
        if (!addedPods.isEmpty()) {
            snapshot.setCurrentPodCount(newCount);
            snapshotService.updateSnapshot(snapshot);
        }
        
        log.info("Scale up result for service {}: {} -> {} pods (target={}, max={}), added={}",
            serviceId, currentCount, newCount, target, maxPodCount, addedPods);
        
        List<String> runningPods = k8sOrchestrator.getRunningPods(serviceId);
        return ScaleResult.builder()
            .serviceId(serviceId)
            .success(!addedPods.isEmpty())
            .scaleType("SCALE_UP")
            .previousPodCount(currentCount)
            .currentPodCount(newCount)
            .maxPodCount(maxPodCount)
            .runningPods(runningPods)
            .addedPods(addedPods)
            .errorMessage(addedPods.isEmpty() ? "Failed to create any pod" : null)
            .build();
    }
    
    private String createPodWithRetry(String serviceId, MergedConfig config, ServiceSnapshot snapshot) {
        for (int retry = 0; retry < SCALE_UP_MAX_RETRIES; retry++) {
            try {
                ScaleResult result = k8sOrchestrator.scaleUp(
                    serviceId, 
                    (snapshot.getCurrentPodCount() != null ? snapshot.getCurrentPodCount() : 0) + 1,
                    config
                );
                
                if (result.isSuccess() && result.getAddedPods() != null && !result.getAddedPods().isEmpty()) {
                    return result.getAddedPods().get(0);
                }
                
                log.warn("Create pod failed for service {}, retry {}/{}", 
                    serviceId, retry + 1, SCALE_UP_MAX_RETRIES);
                
            } catch (Exception e) {
                log.warn("Create pod exception for service {}, retry {}/{}: {}", 
                    serviceId, retry + 1, SCALE_UP_MAX_RETRIES, e.getMessage());
            }
            
            if (retry < SCALE_UP_MAX_RETRIES - 1) {
                try {
                    Thread.sleep(1000L * (retry + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }
    
    public ScaleResult scale(String serviceId, int targetPodCount, String source) {
        log.info("Scale request for service {}, targetPodCount={}, source={}",
            serviceId, targetPodCount, source);
        
        String lockValue = lockService.tryAcquireLock(serviceId);
        if (lockValue == null) {
            return ScaleResult.failed(serviceId, 
                "Failed to acquire lock, another scaling operation may be in progress");
        }
        
        try {
            return doScale(serviceId, targetPodCount, source);
        } finally {
            lockService.releaseLock(serviceId, lockValue);
        }
    }
    
    private ScaleResult doScale(String serviceId, int targetPodCount, String source) {
        List<String> runningPods = k8sOrchestrator.getRunningPods(serviceId);
        int currentCount = runningPods.size();
        
        if (targetPodCount < currentCount) {
            int podsToRemove = currentCount - targetPodCount;
            ScaleResult lastResult = null;
            
            for (int i = 0; i < podsToRemove; i++) {
                List<String> currentPods = k8sOrchestrator.getRunningPods(serviceId);
                if (currentPods.isEmpty()) {
                    break;
                }
                String podToDelete = currentPods.get(currentPods.size() - 1);
                
                lastResult = doScaleDown(serviceId, podToDelete);
                if (!lastResult.isSuccess()) {
                    return lastResult;
                }
            }
            
            return lastResult;
            
        } else if (targetPodCount > currentCount) {
            return doScaleUp(serviceId, targetPodCount);
            
        } else {
            ServiceSnapshot snapshot = snapshotService.getSnapshot(serviceId);
            int maxPodCount = snapshot != null && snapshot.getMaxPodCount() != null 
                ? snapshot.getMaxPodCount() : currentCount;
            return ScaleResult.noChange(serviceId, currentCount, maxPodCount, runningPods);
        }
    }
    
    public ScaleResult getScaleStatus(String serviceId) {
        List<String> runningPods = k8sOrchestrator.getRunningPods(serviceId);
        ServiceSnapshot snapshot = snapshotService.getSnapshot(serviceId);
        
        int maxPodCount = snapshot != null && snapshot.getMaxPodCount() != null 
            ? snapshot.getMaxPodCount() : runningPods.size();
        
        return ScaleResult.noChange(serviceId, runningPods.size(), maxPodCount, runningPods);
    }
    
    /**
     * 任务入队时确保岗位下有可用 Pod。
     * 如果快照存在且当前 Pod 数量为 0，按岗位配置的 maxPodCount 扩容。
     *
     * @param serviceId 服务 ID（= roleId）
     * @return 扩容结果；快照不存在或已有 Pod 时返回 null（无需扩容）
     */
    public ScaleResult ensurePodsForRole(String serviceId) {
        ServiceSnapshot snapshot = snapshotService.getSnapshot(serviceId);
        if (snapshot == null) {
            snapshot = tryRecoverSnapshotFromSources(serviceId);
            if (snapshot == null) {
                log.warn("ensurePodsForRole: 无法恢复 Snapshot，跳过自动扩容: serviceId={}", serviceId);
                return null;
            }
        }
        
        int currentCount = snapshot.getCurrentPodCount() != null ? snapshot.getCurrentPodCount() : 0;
        if (currentCount > 0) {
            return null;
        }
        
        // 二次确认：快照可能与 K8s 实际状态不同步
        List<String> actualPods = k8sOrchestrator.getRunningPods(serviceId);
        if (!actualPods.isEmpty()) {
            snapshot.setCurrentPodCount(actualPods.size());
            snapshotService.updateSnapshot(snapshot);
            log.info("ensurePodsForRole: snapshot was stale for service {}, actual pods={}", 
                serviceId, actualPods.size());
            return null;
        }
        
        log.info("ensurePodsForRole: no pods for service {}, triggering scale-up to maxPodCount", serviceId);
        return scaleUp(serviceId, null, "task_enqueue");
    }
    
    private MergedConfig restoreConfig(ServiceSnapshot snapshot) {
        try {
            String json = snapshot.getOriginalRequestJson();
            
            if (json == null || json.isEmpty()) {
                json = snapshotService.getOriginalRequestJsonFromRedis(snapshot.getServiceId());
                if (json != null) {
                    snapshot.setOriginalRequestJson(json);
                    snapshotService.updateSnapshot(snapshot);
                }
            }
            
            if (json == null || json.isEmpty()) {
                json = rebuildRequestFromBuildRecord(snapshot);
                if (json != null) {
                    snapshot.setOriginalRequestJson(json);
                    snapshotService.updateSnapshot(snapshot);
                }
            }
            
            if (json == null || json.isEmpty()) {
                log.error("Original request JSON is empty for service {} and all fallbacks failed", 
                    snapshot.getServiceId());
                return null;
            }
            
            ServiceBuildRequest request = objectMapper.readValue(json, ServiceBuildRequest.class);
            MergedConfig config = configMergeService.merge(request);
            
            if (snapshot.getAgentImage() != null && !snapshot.getAgentImage().isEmpty()) {
                config.setAgentImage(snapshot.getAgentImage());
                log.info("Restored built agent image for service {}: {}", 
                    snapshot.getServiceId(), snapshot.getAgentImage());
            } else {
                String builtImage = resolveBuiltImageFromRecord(snapshot.getServiceId());
                if (builtImage != null) {
                    config.setAgentImage(builtImage);
                    snapshot.setAgentImage(builtImage);
                    snapshotService.updateSnapshot(snapshot);
                    log.info("Restored built agent image from DB for service {}: {}", 
                        snapshot.getServiceId(), builtImage);
                }
            }
            
            return config;
            
        } catch (Exception e) {
            log.error("Failed to restore config from snapshot: {}", e.getMessage(), e);
            return null;
        }
    }
    
    private String resolveBuiltImageFromRecord(String serviceId) {
        try {
            Long roleId = Long.parseLong(serviceId);
            BuildRecordEntity record = buildRecordService.getLatestByRoleId(roleId);
            if (record != null && BuildRecordEntity.STATUS_SUCCESS.equals(record.getStatus())
                    && record.getImageTag() != null) {
                return record.getImageTag();
            }
        } catch (Exception e) {
            log.warn("Failed to resolve built image from DB for service {}: {}", serviceId, e.getMessage());
        }
        return null;
    }
    
    /**
     * 当内存中无 Snapshot 时，尝试从 Redis + DB 恢复一个可用的 Snapshot。
     * 典型场景：后端重启后 Pod 已结束（Succeeded），SnapshotSyncTask 不会重建该服务的 Snapshot。
     */
    @SuppressWarnings("unchecked")
    private ServiceSnapshot tryRecoverSnapshotFromSources(String serviceId) {
        try {
            Long roleId = Long.parseLong(serviceId);
            BuildRecordEntity record = buildRecordService.getLatestByRoleId(roleId);
            if (record == null) {
                log.warn("tryRecoverSnapshot: 岗位无构建记录，无法恢复 Snapshot: serviceId={}", serviceId);
                return null;
            }
            if (!BuildRecordEntity.STATUS_SUCCESS.equals(record.getStatus())) {
                log.warn("tryRecoverSnapshot: 最新构建记录状态非 SUCCESS，无法恢复: serviceId={}, buildNo={}, status={}",
                        serviceId, record.getBuildNo(), record.getStatus());
                return null;
            }
            
            String originalRequestJson = snapshotService.getOriginalRequestJsonFromRedis(serviceId);
            
            if (originalRequestJson == null) {
                log.info("tryRecoverSnapshot: Redis 中无 originalRequestJson，尝试从 DB 构建记录重建: serviceId={}", serviceId);
                originalRequestJson = rebuildRequestJsonFromRecord(serviceId, record);
            }
            if (originalRequestJson == null) {
                log.warn("tryRecoverSnapshot: 从 Redis 和 DB 均无法恢复 originalRequestJson: serviceId={}, buildNo={}",
                        serviceId, record.getBuildNo());
                return null;
            }
            
            Map<String, Object> config = record.getConfigSnapshot();
            String podMode = config != null && config.get("podMode") != null ? 
                (String) config.get("podMode") : "SIDECAR";
            Integer podCount = config != null && config.get("podCount") != null ?
                ((Number) config.get("podCount")).intValue() : 1;
            
            ServiceSnapshot snapshot = ServiceSnapshot.builder()
                .serviceId(serviceId)
                .userId(record.getCreatorId())
                .originalRequestJson(originalRequestJson)
                .agentImage(record.getImageTag())
                .podMode(podMode)
                .maxPodCount(podCount)
                .currentPodCount(0)
                .runningPodNames(new HashSet<>())
                .nextPodIndex(0)
                .createdAt(java.time.Instant.now())
                .lastActiveAt(java.time.Instant.now())
                .status(com.linkwork.model.enums.SnapshotStatus.ACTIVE)
                .resumeCount(0)
                .build();
            
            snapshotService.updateSnapshot(snapshot);
            log.info("Recovered snapshot from Redis/DB for service {}, maxPodCount={}", 
                serviceId, podCount);
            return snapshot;
            
        } catch (Exception e) {
            log.warn("Failed to recover snapshot for service {}: {}", serviceId, e.getMessage());
            return null;
        }
    }
    
    @SuppressWarnings("unchecked")
    private String rebuildRequestJsonFromRecord(String serviceId, BuildRecordEntity record) {
        try {
            Map<String, Object> config = record.getConfigSnapshot();
            if (config == null) {
                return null;
            }
            ServiceBuildRequest request = ServiceBuildRequest.builder()
                .serviceId(serviceId)
                .userId(record.getCreatorId())
                .roleId(record.getRoleId())
                .roleName(record.getRoleName())
                .deployMode(DeployMode.K8S)
                .buildEnvVars(config.containsKey("buildEnvVars") ? 
                    (Map<String, Object>) config.get("buildEnvVars") : Map.of())
                .podMode(config.containsKey("podMode") && config.get("podMode") != null ? 
                    PodMode.valueOf((String) config.get("podMode")) : PodMode.SIDECAR)
                .podCount(config.containsKey("podCount") && config.get("podCount") != null ?
                    ((Number) config.get("podCount")).intValue() : 1)
                .runnerBaseImage(config.containsKey("runnerBaseImage") ? 
                    (String) config.get("runnerBaseImage") : null)
                .build();
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            log.error("Failed to build request JSON from record for service {}: {}", serviceId, e.getMessage());
            return null;
        }
    }
    
    private String rebuildRequestFromBuildRecord(ServiceSnapshot snapshot) {
        String serviceId = snapshot.getServiceId();
        try {
            Long roleId = Long.parseLong(serviceId);
            BuildRecordEntity record = buildRecordService.getLatestByRoleId(roleId);
            if (record == null || !BuildRecordEntity.STATUS_SUCCESS.equals(record.getStatus())) {
                log.warn("No successful build record found for role {}", roleId);
                return null;
            }
            String json = rebuildRequestJsonFromRecord(serviceId, record);
            if (json != null) {
                log.info("Rebuilt ServiceBuildRequest from DB build record for service {}, buildNo={}", 
                    serviceId, record.getBuildNo());
            }
            return json;
        } catch (NumberFormatException e) {
            log.warn("ServiceId {} is not a valid roleId, cannot reconstruct from build record", serviceId);
            return null;
        } catch (Exception e) {
            log.error("Failed to rebuild request from build record for service {}: {}", 
                serviceId, e.getMessage(), e);
            return null;
        }
    }
}
