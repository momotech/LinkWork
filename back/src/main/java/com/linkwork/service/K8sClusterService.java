package com.linkwork.service;

import com.linkwork.model.dto.*;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * K8s 集群监控 Service — 多命名空间、全量资源查看
 * 复用现有 KubernetesClient Bean
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class K8sClusterService {

    private final KubernetesClient kubernetesClient;

    public List<String> listNamespaces() {
        return kubernetesClient.namespaces().list().getItems().stream()
                .map(ns -> ns.getMetadata().getName())
                .sorted()
                .collect(Collectors.toList());
    }

    public ClusterOverviewDTO getOverview(String namespace) {
        var podList = kubernetesClient.pods().inNamespace(namespace).list().getItems();

        int running = 0, pending = 0, failed = 0, succeeded = 0;
        for (Pod pod : podList) {
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
            switch (phase) {
                case "Running" -> running++;
                case "Pending" -> pending++;
                case "Failed" -> failed++;
                case "Succeeded" -> succeeded++;
            }
        }

        long usedCpu = 0, usedMem = 0;
        try {
            var metrics = kubernetesClient.top().pods().inNamespace(namespace).metrics().getItems();
            for (PodMetrics pm : metrics) {
                for (ContainerMetrics cm : pm.getContainers()) {
                    Quantity cpu = cm.getUsage().get("cpu");
                    Quantity mem = cm.getUsage().get("memory");
                    if (cpu != null) usedCpu += parseMillicores(cpu);
                    if (mem != null) usedMem += parseBytes(mem);
                }
            }
        } catch (Exception e) {
            log.warn("Metrics not available for namespace {}: {}", namespace, e.getMessage());
        }

        long totalCpu = 0, totalMem = 0;
        int nodeCount = 0;
        try {
            var nodes = kubernetesClient.nodes().list().getItems();
            nodeCount = nodes.size();
            for (Node node : nodes) {
                Quantity cpuCap = node.getStatus().getAllocatable().get("cpu");
                Quantity memCap = node.getStatus().getAllocatable().get("memory");
                if (cpuCap != null) totalCpu += parseMillicores(cpuCap);
                if (memCap != null) totalMem += parseBytes(memCap);
            }
        } catch (Exception e) {
            log.warn("Failed to get node info: {}", e.getMessage());
        }

        int pgCount = 0;
        try {
            pgCount = kubernetesClient
                    .genericKubernetesResources("scheduling.volcano.sh/v1beta1", "PodGroup")
                    .inNamespace(namespace).list().getItems().size();
        } catch (Exception ignored) {}

        return ClusterOverviewDTO.builder()
                .namespace(namespace)
                .totalPods(podList.size())
                .runningPods(running)
                .pendingPods(pending)
                .failedPods(failed)
                .succeededPods(succeeded)
                .totalCpuMillicores(totalCpu)
                .usedCpuMillicores(usedCpu)
                .cpuUsagePercent(totalCpu > 0 ? Math.round(usedCpu * 10000.0 / totalCpu) / 100.0 : null)
                .totalMemoryBytes(totalMem)
                .usedMemoryBytes(usedMem)
                .memoryUsagePercent(totalMem > 0 ? Math.round(usedMem * 10000.0 / totalMem) / 100.0 : null)
                .podGroupCount(pgCount)
                .nodeCount(nodeCount)
                .build();
    }

    public List<ClusterNodeInfo> listNodes() {
        var nodes = kubernetesClient.nodes().list().getItems();

        Map<String, NodeMetrics> metricsMap = new HashMap<>();
        try {
            kubernetesClient.top().nodes().metrics().getItems()
                    .forEach(nm -> metricsMap.put(nm.getMetadata().getName(), nm));
        } catch (Exception e) {
            log.warn("Node metrics not available: {}", e.getMessage());
        }

        Map<String, Integer> podCounts = new HashMap<>();
        try {
            kubernetesClient.pods().inAnyNamespace().list().getItems()
                    .forEach(p -> {
                        String node = p.getSpec().getNodeName();
                        if (node != null) podCounts.merge(node, 1, Integer::sum);
                    });
        } catch (Exception e) {
            log.warn("Failed to count pods per node: {}", e.getMessage());
        }

        return nodes.stream().map(node -> {
            String name = node.getMetadata().getName();
            String status = node.getStatus().getConditions().stream()
                    .filter(c -> "Ready".equals(c.getType()))
                    .findFirst()
                    .map(c -> "True".equals(c.getStatus()) ? "Ready" : "NotReady")
                    .orElse("Unknown");

            List<String> roles = node.getMetadata().getLabels().entrySet().stream()
                    .filter(e -> e.getKey().startsWith("node-role.kubernetes.io/"))
                    .map(e -> e.getKey().substring("node-role.kubernetes.io/".length()))
                    .collect(Collectors.toList());

            long cpuCap = parseMillicores(node.getStatus().getCapacity().get("cpu"));
            long cpuAlloc = parseMillicores(node.getStatus().getAllocatable().get("cpu"));
            long memCap = parseBytes(node.getStatus().getCapacity().get("memory"));
            long memAlloc = parseBytes(node.getStatus().getAllocatable().get("memory"));
            int podCap = parseIntQuantity(node.getStatus().getCapacity().get("pods"));

            long cpuUsage = 0, memUsage = 0;
            NodeMetrics nm = metricsMap.get(name);
            if (nm != null) {
                Quantity cpu = nm.getUsage().get("cpu");
                Quantity mem = nm.getUsage().get("memory");
                if (cpu != null) cpuUsage = parseMillicores(cpu);
                if (mem != null) memUsage = parseBytes(mem);
            }

            return ClusterNodeInfo.builder()
                    .name(name)
                    .status(status)
                    .roles(roles)
                    .kubeletVersion(node.getStatus().getNodeInfo().getKubeletVersion())
                    .cpuCapacity(cpuCap)
                    .cpuAllocatable(cpuAlloc)
                    .cpuUsage(cpuUsage)
                    .cpuUsagePercent(cpuAlloc > 0 ? Math.round(cpuUsage * 10000.0 / cpuAlloc) / 100.0 : null)
                    .memCapacity(memCap)
                    .memAllocatable(memAlloc)
                    .memUsage(memUsage)
                    .memUsagePercent(memAlloc > 0 ? Math.round(memUsage * 10000.0 / memAlloc) / 100.0 : null)
                    .podCount(podCounts.getOrDefault(name, 0))
                    .podCapacity(podCap)
                    .build();
        }).collect(Collectors.toList());
    }

    public List<ClusterPodInfo> listPods(String namespace, String statusFilter, String nodeFilter, String podGroupFilter) {
        var podList = kubernetesClient.pods().inNamespace(namespace).list().getItems();

        Map<String, PodMetrics> metricsMap = new HashMap<>();
        try {
            kubernetesClient.top().pods().inNamespace(namespace).metrics().getItems()
                    .forEach(pm -> metricsMap.put(pm.getMetadata().getName(), pm));
        } catch (Exception e) {
            log.warn("Pod metrics not available for {}: {}", namespace, e.getMessage());
        }

        Map<String, String> podGroupPhases = new HashMap<>();
        try {
            kubernetesClient.genericKubernetesResources("scheduling.volcano.sh/v1beta1", "PodGroup")
                    .inNamespace(namespace).list().getItems().forEach(pg -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> st = (Map<String, Object>) pg.getAdditionalProperties().get("status");
                        String phase = st != null ? String.valueOf(st.getOrDefault("phase", "Unknown")) : "Unknown";
                        podGroupPhases.put(pg.getMetadata().getName(), phase);
                    });
        } catch (Exception ignored) {}

        return podList.stream()
                .map(pod -> buildClusterPodInfo(pod, metricsMap, podGroupPhases))
                .filter(p -> statusFilter == null || statusFilter.isEmpty() || p.getPhase().equalsIgnoreCase(statusFilter))
                .filter(p -> nodeFilter == null || nodeFilter.isEmpty() || nodeFilter.equals(p.getNodeName()))
                .filter(p -> podGroupFilter == null || podGroupFilter.isEmpty() || podGroupFilter.equals(p.getPodGroupName()))
                .collect(Collectors.toList());
    }

    public List<PodGroupStatusInfo> listPodGroups(String namespace) {
        List<PodGroupStatusInfo> result = new ArrayList<>();
        try {
            var pgList = kubernetesClient.genericKubernetesResources("scheduling.volcano.sh/v1beta1", "PodGroup")
                    .inNamespace(namespace).list().getItems();
            for (var pg : pgList) {
                @SuppressWarnings("unchecked")
                Map<String, Object> status = (Map<String, Object>) pg.getAdditionalProperties().get("status");
                @SuppressWarnings("unchecked")
                Map<String, Object> spec = (Map<String, Object>) pg.getAdditionalProperties().get("spec");

                result.add(PodGroupStatusInfo.builder()
                        .name(pg.getMetadata().getName())
                        .phase(status != null ? String.valueOf(status.getOrDefault("phase", "Unknown")) : "Unknown")
                        .minMember(spec != null && spec.get("minMember") != null ? ((Number) spec.get("minMember")).intValue() : null)
                        .running(status != null && status.get("running") != null ? ((Number) status.get("running")).intValue() : 0)
                        .succeeded(status != null && status.get("succeeded") != null ? ((Number) status.get("succeeded")).intValue() : 0)
                        .failed(status != null && status.get("failed") != null ? ((Number) status.get("failed")).intValue() : 0)
                        .pending(status != null && status.get("pending") != null ? ((Number) status.get("pending")).intValue() : 0)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Failed to list PodGroups in {}: {}", namespace, e.getMessage());
        }
        return result;
    }

    public PodLogResponseDTO getPodLogs(String namespace, String podName, String container, int tailLines) {
        try {
            var pod = kubernetesClient.pods().inNamespace(namespace).withName(podName).get();
            if (pod == null) {
                return PodLogResponseDTO.builder().podName(podName).namespace(namespace).logs("Pod not found").tailLines(tailLines).build();
            }

            String targetContainer = container;
            if (targetContainer == null || targetContainer.isEmpty()) {
                var cs = pod.getSpec().getContainers();
                if (!cs.isEmpty()) targetContainer = cs.get(0).getName();
            }

            String logContent;
            try (InputStream is = kubernetesClient.pods().inNamespace(namespace).withName(podName)
                    .inContainer(targetContainer).tailingLines(tailLines).getLogInputStream()) {
                logContent = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines().collect(Collectors.joining("\n"));
            }

            return PodLogResponseDTO.builder()
                    .podName(podName).namespace(namespace).containerName(targetContainer)
                    .logs(logContent).tailLines(tailLines).build();
        } catch (Exception e) {
            log.error("Failed to get logs for {}/{}: {}", namespace, podName, e.getMessage());
            return PodLogResponseDTO.builder().podName(podName).namespace(namespace)
                    .logs("Error: " + e.getMessage()).tailLines(tailLines).build();
        }
    }

    public List<ClusterEventDTO> listEvents(String namespace, int limit) {
        var events = kubernetesClient.v1().events().inNamespace(namespace).list().getItems();
        events.sort((a, b) -> {
            String ta = a.getLastTimestamp() != null ? a.getLastTimestamp() : "";
            String tb = b.getLastTimestamp() != null ? b.getLastTimestamp() : "";
            return tb.compareTo(ta);
        });

        return events.stream().limit(limit).map(e -> ClusterEventDTO.builder()
                .type(e.getType())
                .reason(e.getReason())
                .message(e.getMessage())
                .objectKind(e.getInvolvedObject() != null ? e.getInvolvedObject().getKind() : null)
                .objectName(e.getInvolvedObject() != null ? e.getInvolvedObject().getName() : null)
                .namespace(e.getMetadata().getNamespace())
                .firstTimestamp(e.getFirstTimestamp())
                .lastTimestamp(e.getLastTimestamp())
                .count(e.getCount())
                .build()
        ).collect(Collectors.toList());
    }

    public List<ClusterEventDTO> listPodEvents(String namespace, String podName) {
        return kubernetesClient.v1().events().inNamespace(namespace).list().getItems().stream()
                .filter(e -> e.getInvolvedObject() != null && podName.equals(e.getInvolvedObject().getName()))
                .map(e -> ClusterEventDTO.builder()
                        .type(e.getType())
                        .reason(e.getReason())
                        .message(e.getMessage())
                        .objectKind(e.getInvolvedObject().getKind())
                        .objectName(e.getInvolvedObject().getName())
                        .namespace(namespace)
                        .firstTimestamp(e.getFirstTimestamp())
                        .lastTimestamp(e.getLastTimestamp())
                        .count(e.getCount())
                        .build()
                ).collect(Collectors.toList());
    }

    public void deletePod(String namespace, String podName) {
        kubernetesClient.pods().inNamespace(namespace).withName(podName).delete();
        log.info("Deleted pod {}/{}", namespace, podName);
    }

    // ─── private helpers ─────────────────────────────────────────────

    private ClusterPodInfo buildClusterPodInfo(Pod pod, Map<String, PodMetrics> metricsMap, Map<String, String> podGroupPhases) {
        String name = pod.getMetadata().getName();
        Map<String, String> annotations = pod.getMetadata().getAnnotations();
        Map<String, String> labels = pod.getMetadata().getLabels();

        String pgName = annotations != null ? annotations.get("scheduling.volcano.sh/group-name") : null;
        String pgPhase = pgName != null ? podGroupPhases.getOrDefault(pgName, "Unknown") : null;
        String serviceId = labels != null ? labels.get("service-id") : null;
        String userId = labels != null ? labels.get("user-id") : null;

        List<ContainerStatusInfo> containers = new ArrayList<>();
        int totalRestarts = 0;

        if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
            for (var cs : pod.getStatus().getContainerStatuses()) {
                String state = "waiting";
                String reason = null;
                Integer exitCode = null;
                if (cs.getState().getRunning() != null) state = "running";
                else if (cs.getState().getTerminated() != null) {
                    state = "terminated";
                    reason = cs.getState().getTerminated().getReason();
                    exitCode = cs.getState().getTerminated().getExitCode();
                } else if (cs.getState().getWaiting() != null) {
                    reason = cs.getState().getWaiting().getReason();
                }

                totalRestarts += cs.getRestartCount();
                containers.add(ContainerStatusInfo.builder()
                        .name(cs.getName()).ready(cs.getReady()).state(state)
                        .reason(reason).exitCode(exitCode).restartCount(cs.getRestartCount())
                        .build());
            }
        }

        List<String> images = pod.getSpec().getContainers().stream()
                .map(Container::getImage).collect(Collectors.toList());

        String startTime = null;
        String age = "";
        if (pod.getStatus() != null && pod.getStatus().getStartTime() != null) {
            startTime = pod.getStatus().getStartTime();
            try {
                Duration d = Duration.between(Instant.parse(startTime), Instant.now());
                if (d.toDays() > 0) age = d.toDays() + "d";
                else if (d.toHours() > 0) age = d.toHours() + "h";
                else age = d.toMinutes() + "m";
            } catch (Exception ignored) { age = "N/A"; }
        }

        ResourceUsageInfo resUsage = null;
        PodMetrics pm = metricsMap.get(name);
        if (pm != null) {
            long cpuUsage = 0, memUsage = 0;
            for (ContainerMetrics cm : pm.getContainers()) {
                Quantity cpu = cm.getUsage().get("cpu");
                Quantity mem = cm.getUsage().get("memory");
                if (cpu != null) cpuUsage += parseMillicores(cpu);
                if (mem != null) memUsage += parseBytes(mem);
            }
            resUsage = ResourceUsageInfo.builder()
                    .cpuMillicores(cpuUsage).cpuUsage(formatMillicores(cpuUsage))
                    .memoryBytes(memUsage).memoryUsage(formatBytes(memUsage))
                    .build();
        }

        return ClusterPodInfo.builder()
                .name(name)
                .namespace(pod.getMetadata().getNamespace())
                .phase(pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown")
                .nodeName(pod.getSpec().getNodeName())
                .podGroupName(pgName)
                .podGroupPhase(pgPhase)
                .serviceId(serviceId)
                .userId(userId)
                .containers(containers)
                .restartCount(totalRestarts)
                .startTime(startTime)
                .age(age)
                .images(images)
                .resourceUsage(resUsage)
                .build();
    }

    private long parseMillicores(Quantity q) {
        if (q == null) return 0;
        try {
            double value = Double.parseDouble(q.getAmount());
            String fmt = q.getFormat();
            if (fmt != null) {
                return switch (fmt) {
                    case "n" -> (long) (value / 1_000_000);
                    case "u" -> (long) (value / 1_000);
                    case "m" -> (long) value;
                    default -> (long) (value * 1000);
                };
            }
            return (long) (value * 1000);
        } catch (NumberFormatException e) { return 0; }
    }

    private long parseBytes(Quantity q) {
        if (q == null) return 0;
        try {
            double value = Double.parseDouble(q.getAmount());
            String fmt = q.getFormat();
            if (fmt != null) {
                return switch (fmt) {
                    case "Ki" -> (long) (value * 1024);
                    case "Mi" -> (long) (value * 1024 * 1024);
                    case "Gi" -> (long) (value * 1024 * 1024 * 1024);
                    case "K" -> (long) (value * 1000);
                    case "M" -> (long) (value * 1000_000);
                    case "G" -> (long) (value * 1000_000_000);
                    default -> (long) value;
                };
            }
            return (long) value;
        } catch (NumberFormatException e) { return 0; }
    }

    private int parseIntQuantity(Quantity q) {
        if (q == null) return 0;
        try { return Integer.parseInt(q.getAmount()); }
        catch (Exception e) { return 0; }
    }

    private String formatMillicores(long millicores) {
        if (millicores >= 1000) return String.format("%.2f", millicores / 1000.0);
        return millicores + "m";
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format("%.2fGi", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format("%.2fMi", bytes / (1024.0 * 1024));
        if (bytes >= 1024) return String.format("%.2fKi", bytes / 1024.0);
        return bytes + "B";
    }
}
