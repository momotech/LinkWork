package com.linkwork.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.EnvConfig;
import com.linkwork.model.dto.ServiceBuildRequest;
import com.linkwork.model.dto.ServiceBuildResult;
import com.linkwork.model.dto.ServiceSnapshot;
import com.linkwork.model.enums.DeployMode;
import com.linkwork.model.enums.PodMode;
import com.linkwork.model.enums.SnapshotStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 服务快照管理服务
 * 
 * 用于快速重启功能：
 * 1. Service 创建成功后保存快照（原始请求、运行节点）
 * 2. Service 关闭时更新快照（记录 shutdownAt）
 * 3. Resume 时查询快照，获取原始请求和上次运行节点
 * 
 * 注：当前使用内存存储，生产环境建议使用 Redis 或 DB
 */
@Service
@Slf4j
public class ServiceSnapshotService {
    
    private final ObjectMapper objectMapper;
    private final EnvConfig envConfig;
    private final StringRedisTemplate redisTemplate;
    
    private static final String REDIS_KEY_PREFIX = "service:snapshot:request:";
    private static final Duration REDIS_TTL = Duration.ofDays(30);
    
    private final Map<String, ServiceSnapshot> snapshotStore = new ConcurrentHashMap<>();
    
    public ServiceSnapshotService(ObjectMapper objectMapper, EnvConfig envConfig,
                                  StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.envConfig = envConfig;
        this.redisTemplate = redisTemplate;
    }
    
    /**
     * Service 创建成功后保存快照
     * 
     * @param request 原始构建请求
     * @param result 构建结果
     */
    public void saveSnapshot(ServiceBuildRequest request, ServiceBuildResult result) {
        try {
            String originalRequestJson = objectMapper.writeValueAsString(request);
            
            // 获取初始 Pod 数量作为最大值
            int podCount = request.getPodCount() != null && request.getPodCount() > 0 
                ? request.getPodCount() : 1;
            
            // 初始化 runningPodNames
            Set<String> runningPodNames = new HashSet<>();
            if (result.getPodNames() != null) {
                runningPodNames.addAll(result.getPodNames());
            }
            
            ServiceSnapshot snapshot = ServiceSnapshot.builder()
                .serviceId(request.getServiceId())
                .userId(request.getUserId())
                .originalRequestJson(originalRequestJson)
                // 保存构建后的 Agent 镜像地址
                .agentImage(result.getBuiltAgentImage())
                .podMode(resolvePodMode(request).name())
                .lastScheduledNode(result.getScheduledNode())
                .maxPodCount(podCount)              // 初始配置的最大 Pod 数量
                .currentPodCount(podCount)          // 当前 Pod 数量
                .runningPodNames(runningPodNames)   // 运行中的 Pod 名称列表
                .nextPodIndex(podCount)             // 下一个 Pod 序号
                .createdAt(Instant.now())
                .lastActiveAt(Instant.now())
                .status(SnapshotStatus.ACTIVE)
                .resumeCount(0)
                .build();
            
            snapshotStore.put(request.getServiceId(), snapshot);
            
            persistRequestToRedis(request.getServiceId(), originalRequestJson);
            
            log.info("Saved snapshot for service {}, scheduledNode={}, maxPodCount={}, pods={}", 
                request.getServiceId(), result.getScheduledNode(), podCount, runningPodNames);
            
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize request for service {}: {}", 
                request.getServiceId(), e.getMessage());
        }
    }

    private PodMode resolvePodMode(ServiceBuildRequest request) {
        if (request.getPodMode() != null) {
            return request.getPodMode();
        }
        if (request.getDeployMode() == DeployMode.COMPOSE) {
            return PodMode.ALONE;
        }
        if (envConfig.getPodModeRules() == null || envConfig.getPodModeRules().getDefaultMode() == null) {
            throw new IllegalStateException("未配置默认运行模式，无法保存服务快照");
        }
        return envConfig.getPodModeRules().getDefaultMode();
    }
    
    /**
     * Service 关闭时更新快照
     * 
     * @param serviceId 服务 ID
     */
    public void onServiceShutdown(String serviceId) {
        ServiceSnapshot snapshot = snapshotStore.get(serviceId);
        if (snapshot != null) {
            snapshot.setShutdownAt(Instant.now());
            log.info("Updated snapshot shutdown time for service {}", serviceId);
        }
    }
    
    /**
     * 查询可用快照
     * 
     * @param serviceId 服务 ID
     * @return 快照（如果存在且未过期）
     */
    public Optional<ServiceSnapshot> findActiveSnapshot(String serviceId) {
        ServiceSnapshot snapshot = snapshotStore.get(serviceId);
        
        if (snapshot == null) {
            log.debug("No snapshot found for service {}", serviceId);
            return Optional.empty();
        }
        
        if (snapshot.getStatus() != SnapshotStatus.ACTIVE) {
            log.debug("Snapshot for service {} is not active: {}", serviceId, snapshot.getStatus());
            return Optional.empty();
        }
        
        if (snapshot.isExpired()) {
            log.info("Snapshot for service {} is expired", serviceId);
            snapshot.setStatus(SnapshotStatus.EXPIRED);
            return Optional.empty();
        }
        
        return Optional.of(snapshot);
    }
    
    /**
     * 从快照恢复原始请求
     * 
     * @param snapshot 快照
     * @return 原始请求
     */
    public ServiceBuildRequest restoreRequest(ServiceSnapshot snapshot) {
        try {
            return objectMapper.readValue(snapshot.getOriginalRequestJson(), ServiceBuildRequest.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize request for service {}: {}", 
                snapshot.getServiceId(), e.getMessage());
            return null;
        }
    }
    
    /**
     * 恢复后更新快照
     * 
     * @param serviceId 服务 ID
     * @param newNode 新的调度节点
     */
    public void onServiceResumed(String serviceId, String newNode) {
        ServiceSnapshot snapshot = snapshotStore.get(serviceId);
        if (snapshot != null) {
            snapshot.setLastScheduledNode(newNode);
            snapshot.setLastActiveAt(Instant.now());
            snapshot.setShutdownAt(null);
            snapshot.setResumeCount(snapshot.getResumeCount() + 1);
            log.info("Updated snapshot for resumed service {}, newNode={}, resumeCount={}", 
                serviceId, newNode, snapshot.getResumeCount());
        }
    }
    
    /**
     * 删除快照
     * 
     * @param serviceId 服务 ID
     */
    public void deleteSnapshot(String serviceId) {
        snapshotStore.remove(serviceId);
        log.info("Deleted snapshot for service {}", serviceId);
    }
    
    /**
     * 获取快照信息
     * 
     * @param serviceId 服务 ID
     * @return 快照，不存在返回 null
     */
    public ServiceSnapshot getSnapshot(String serviceId) {
        return snapshotStore.get(serviceId);
    }
    
    /**
     * 更新快照
     * 
     * @param snapshot 快照
     */
    public void updateSnapshot(ServiceSnapshot snapshot) {
        if (snapshot != null && snapshot.getServiceId() != null) {
            snapshotStore.put(snapshot.getServiceId(), snapshot);
            log.debug("Updated snapshot for service {}", snapshot.getServiceId());
        }
    }
    
    /**
     * 获取所有需要同步的快照
     * 
     * 包含：
     * 1. 活跃且有运行中 Pod 的快照
     * 2. currentPodCount=0 但 shutdownAt 未设置的快照（需要标记过期时间）
     * 
     * @return 需要同步的快照列表
     */
    public List<ServiceSnapshot> findAllActive() {
        return snapshotStore.values().stream()
            .filter(s -> s.getStatus() == SnapshotStatus.ACTIVE)
            .filter(s -> !s.isExpired())
            .filter(s -> {
                int podCount = s.getCurrentPodCount() != null ? s.getCurrentPodCount() : 0;
                // 有运行中的 Pod，需要同步
                if (podCount > 0) {
                    return true;
                }
                // Pod 数量为 0 但还没设置 shutdownAt，需要同步以标记过期时间
                return s.getShutdownAt() == null;
            })
            .collect(Collectors.toList());
    }
    
    /**
     * 获取并递增下一个 Pod 序号
     * 
     * @param serviceId 服务 ID
     * @return 下一个 Pod 序号
     */
    public int getAndIncrementNextPodIndex(String serviceId) {
        ServiceSnapshot snapshot = snapshotStore.get(serviceId);
        if (snapshot == null) {
            return 0;
        }
        int nextIndex = snapshot.getNextPodIndex() != null ? snapshot.getNextPodIndex() : 0;
        snapshot.setNextPodIndex(nextIndex + 1);
        return nextIndex;
    }
    
    /**
     * 从 K8s 运行状态反向重建 Snapshot（后端重启后恢复用）
     * 
     * <p>尝试从 Redis 恢复 originalRequestJson；若 Redis 中没有则为 null。
     * 
     * @param serviceId      服务 ID
     * @param runningPodNames 当前运行中的 Pod 名称列表
     * @param userId          用户 ID（从 Pod label 提取）
     * @param podMode         Pod 模式（从 Pod label 提取，如 "sidecar"/"alone"）
     * @param scheduledNode   调度节点（从 Pod spec 提取）
     * @return 重建的 Snapshot
     */
    public ServiceSnapshot rebuildFromK8s(String serviceId, Set<String> runningPodNames,
                                          String userId, String podMode, String scheduledNode) {
        int podCount = runningPodNames.size();
        
        int maxIndex = runningPodNames.stream()
            .map(name -> {
                String[] parts = name.split("-");
                try {
                    return Integer.parseInt(parts[parts.length - 1]);
                } catch (NumberFormatException e) {
                    return -1;
                }
            })
            .filter(i -> i >= 0)
            .max(Integer::compareTo)
            .orElse(0);
        
        String originalRequestJson = loadRequestFromRedis(serviceId);
        
        String agentImage = null;
        if (originalRequestJson != null) {
            try {
                ServiceBuildRequest req = objectMapper.readValue(originalRequestJson, ServiceBuildRequest.class);
                agentImage = req.getBuildEnvVars() != null ? 
                    (String) req.getBuildEnvVars().get("AGENT_IMAGE") : null;
            } catch (Exception e) {
                log.warn("Failed to parse restored request for service {}: {}", serviceId, e.getMessage());
            }
        }
        
        ServiceSnapshot snapshot = ServiceSnapshot.builder()
            .serviceId(serviceId)
            .userId(userId)
            .originalRequestJson(originalRequestJson)
            .agentImage(agentImage)
            .podMode(podMode)
            .lastScheduledNode(scheduledNode)
            .maxPodCount(podCount)
            .currentPodCount(podCount)
            .runningPodNames(runningPodNames)
            .nextPodIndex(maxIndex + 1)
            .createdAt(Instant.now())
            .lastActiveAt(Instant.now())
            .status(SnapshotStatus.ACTIVE)
            .resumeCount(0)
            .build();
        
        snapshotStore.put(serviceId, snapshot);
        log.info("Rebuilt snapshot from K8s for service {}: pods={}, node={}, podMode={}, hasOriginalRequest={}", 
            serviceId, runningPodNames, scheduledNode, podMode, originalRequestJson != null);
        
        return snapshot;
    }
    
    /**
     * 判断是否已有某个 serviceId 的 Snapshot
     */
    public boolean hasSnapshot(String serviceId) {
        return snapshotStore.containsKey(serviceId);
    }
    
    /**
     * 从 Redis 加载 originalRequestJson（供外部在 snapshot 缺失时使用）
     */
    public String getOriginalRequestJsonFromRedis(String serviceId) {
        return loadRequestFromRedis(serviceId);
    }
    
    private void persistRequestToRedis(String serviceId, String requestJson) {
        try {
            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + serviceId, requestJson, REDIS_TTL);
        } catch (Exception e) {
            log.warn("Failed to persist request to Redis for service {}: {}", serviceId, e.getMessage());
        }
    }
    
    private String loadRequestFromRedis(String serviceId) {
        try {
            String json = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + serviceId);
            if (json != null) {
                log.info("Recovered originalRequestJson from Redis for service {}", serviceId);
            }
            return json;
        } catch (Exception e) {
            log.warn("Failed to load request from Redis for service {}: {}", serviceId, e.getMessage());
            return null;
        }
    }
}
