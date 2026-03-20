package com.linkwork.controller;

import com.linkwork.common.ApiResponse;
import com.linkwork.context.UserContext;
import com.linkwork.context.UserInfo;
import com.linkwork.model.dto.*;
import com.linkwork.service.K8sClusterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * K8s 集群监控 API
 * 仅允许配置的 workId 用户访问
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/k8s-monitor")
@RequiredArgsConstructor
public class K8sClusterController {

    private final K8sClusterService k8sClusterService;

    @Value("${schedule.cluster.namespace:ai-worker}")
    private String defaultNamespace;

    @Value("${robot.k8s-monitor.allowed-users:}")
    private String allowedUsersConfig;

    private Set<String> getAllowedUsers() {
        Set<String> set = new HashSet<>();
        for (String id : allowedUsersConfig.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return set;
    }

    private boolean isUserAllowed(UserInfo user) {
        if (user == null) return false;
        Set<String> allowed = getAllowedUsers();
        if (user.getWorkId() != null && allowed.contains(user.getWorkId().trim())) return true;
        if (user.getUserId() != null && allowed.contains(user.getUserId().trim())) return true;
        return false;
    }

    private String resolveNamespace(String namespace) {
        return (namespace == null || namespace.isBlank()) ? defaultNamespace : namespace;
    }

    private void checkPermission() {
        UserInfo user = UserContext.get();
        if (user == null) {
            throw new SecurityException("未登录");
        }
        if (!isUserAllowed(user)) {
            log.warn("K8s Monitor 访问被拒绝: userId={}, workId={}, name={}", user.getUserId(), user.getWorkId(), user.getName());
            throw new SecurityException("无权访问 K8s 集群监控");
        }
    }

    @GetMapping("/access-check")
    public ApiResponse<Boolean> checkAccess() {
        UserInfo user = UserContext.get();
        if (user == null) return ApiResponse.success(false);
        log.debug("K8s Monitor access-check: userId={}, workId={}, name={}", user.getUserId(), user.getWorkId(), user.getName());
        return ApiResponse.success(isUserAllowed(user));
    }

    @GetMapping("/namespaces")
    public ApiResponse<List<String>> namespaces() {
        checkPermission();
        return ApiResponse.success(k8sClusterService.listNamespaces());
    }

    @GetMapping("/overview")
    public ApiResponse<ClusterOverviewDTO> overview(@RequestParam(required = false) String namespace) {
        checkPermission();
        return ApiResponse.success(k8sClusterService.getOverview(resolveNamespace(namespace)));
    }

    @GetMapping("/nodes")
    public ApiResponse<List<ClusterNodeInfo>> nodes() {
        checkPermission();
        return ApiResponse.success(k8sClusterService.listNodes());
    }

    @GetMapping("/pods")
    public ApiResponse<List<ClusterPodInfo>> pods(
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String node,
            @RequestParam(required = false) String podGroup) {
        checkPermission();
        return ApiResponse.success(k8sClusterService.listPods(resolveNamespace(namespace), status, node, podGroup));
    }

    @GetMapping("/podgroups")
    public ApiResponse<List<PodGroupStatusInfo>> podGroups(@RequestParam(required = false) String namespace) {
        checkPermission();
        return ApiResponse.success(k8sClusterService.listPodGroups(resolveNamespace(namespace)));
    }

    @GetMapping("/pods/{podName}/logs")
    public ApiResponse<PodLogResponseDTO> podLogs(
            @PathVariable String podName,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String container,
            @RequestParam(defaultValue = "200") int tailLines) {
        checkPermission();
        return ApiResponse.success(k8sClusterService.getPodLogs(resolveNamespace(namespace), podName, container, tailLines));
    }

    @GetMapping("/events")
    public ApiResponse<List<ClusterEventDTO>> events(
            @RequestParam(required = false) String namespace,
            @RequestParam(defaultValue = "50") int limit) {
        checkPermission();
        return ApiResponse.success(k8sClusterService.listEvents(resolveNamespace(namespace), limit));
    }

    @GetMapping("/pods/{podName}/events")
    public ApiResponse<List<ClusterEventDTO>> podEvents(
            @PathVariable String podName,
            @RequestParam(required = false) String namespace) {
        checkPermission();
        return ApiResponse.success(k8sClusterService.listPodEvents(resolveNamespace(namespace), podName));
    }

    @DeleteMapping("/pods/{podName}")
    public ApiResponse<String> deletePod(
            @PathVariable String podName,
            @RequestParam(required = false) String namespace) {
        checkPermission();
        k8sClusterService.deletePod(resolveNamespace(namespace), podName);
        return ApiResponse.success("Pod " + podName + " deleted");
    }

    @ExceptionHandler(SecurityException.class)
    public ApiResponse<Void> handleSecurity(SecurityException e) {
        return ApiResponse.error(40300, e.getMessage());
    }
}
