package com.linkwork.controller.v1;

import com.linkwork.context.UserContext;
import com.linkwork.context.UserInfo;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/k8s-monitor")
@ConditionalOnBean(KubernetesClient.class)
public class K8sMonitorController {

    @Autowired
    private KubernetesClient kubernetesClient;

    @Value("${linkwork.k8s-monitor.namespace:ai-worker}")
    private String defaultNamespace;

    @Value("${linkwork.k8s-monitor.allowed-users:}")
    private String allowedUsersConfig;

    private Set<String> getAllowedUsers() {
        Set<String> set = new HashSet<>();
        if (allowedUsersConfig != null) {
            for (String id : allowedUsersConfig.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) set.add(trimmed);
            }
        }
        return set;
    }

    private boolean isUserAllowed(UserInfo user) {
        if (user == null) return false;
        Set<String> allowed = getAllowedUsers();
        if (allowed.isEmpty()) return true;
        if (user.getWorkId() != null && allowed.contains(user.getWorkId().trim())) return true;
        return user.getUserId() != null && allowed.contains(user.getUserId().trim());
    }

    private void checkPermission() {
        UserInfo user = UserContext.get();
        if (user == null) throw new SecurityException("Not authenticated");
        if (!isUserAllowed(user)) throw new SecurityException("Access denied to K8s monitor");
    }

    private String ns(String namespace) {
        return (namespace == null || namespace.isBlank()) ? defaultNamespace : namespace;
    }

    @GetMapping("/access-check")
    public ResponseEntity<Map<String, Object>> checkAccess() {
        UserInfo user = UserContext.get();
        boolean allowed = user != null && isUserAllowed(user);
        return ResponseEntity.ok(Map.of("code", 0, "data", allowed));
    }

    @GetMapping("/namespaces")
    public ResponseEntity<Map<String, Object>> namespaces() {
        checkPermission();
        List<String> names = kubernetesClient.namespaces().list().getItems().stream()
                .map(ns -> ns.getMetadata().getName()).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("code", 0, "data", names));
    }

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(@RequestParam(required = false) String namespace) {
        checkPermission();
        String ns = ns(namespace);
        PodList podList = kubernetesClient.pods().inNamespace(ns).list();
        long total = podList.getItems().size();
        long running = podList.getItems().stream()
                .filter(p -> "Running".equals(p.getStatus().getPhase())).count();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("namespace", ns);
        data.put("totalPods", total);
        data.put("runningPods", running);
        data.put("pendingPods", total - running);
        return ResponseEntity.ok(Map.of("code", 0, "data", data));
    }

    @GetMapping("/nodes")
    public ResponseEntity<Map<String, Object>> nodes() {
        checkPermission();
        List<Map<String, Object>> items = kubernetesClient.nodes().list().getItems().stream().map(node -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", node.getMetadata().getName());
            m.put("labels", node.getMetadata().getLabels());
            NodeStatus status = node.getStatus();
            if (status != null && status.getConditions() != null) {
                m.put("conditions", status.getConditions().stream()
                        .map(c -> Map.of("type", c.getType(), "status", c.getStatus()))
                        .collect(Collectors.toList()));
            }
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("code", 0, "data", items));
    }

    @GetMapping("/pods")
    public ResponseEntity<Map<String, Object>> pods(
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String status) {
        checkPermission();
        List<Pod> allPods = kubernetesClient.pods().inNamespace(ns(namespace)).list().getItems();
        if (status != null && !status.isBlank()) {
            allPods = allPods.stream()
                    .filter(p -> status.equalsIgnoreCase(p.getStatus().getPhase()))
                    .collect(Collectors.toList());
        }
        List<Map<String, Object>> items = allPods.stream().map(this::podToMap).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("code", 0, "data", items));
    }

    @GetMapping("/pods/{podName}/logs")
    public ResponseEntity<Map<String, Object>> podLogs(
            @PathVariable String podName,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String container,
            @RequestParam(defaultValue = "200") int tailLines) {
        checkPermission();
        var logReq = kubernetesClient.pods().inNamespace(ns(namespace)).withName(podName);
        String logs;
        if (container != null && !container.isBlank()) {
            logs = logReq.inContainer(container).tailingLines(tailLines).getLog();
        } else {
            logs = logReq.tailingLines(tailLines).getLog();
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", Map.of("podName", podName, "logs", logs)));
    }

    @GetMapping("/events")
    public ResponseEntity<Map<String, Object>> events(
            @RequestParam(required = false) String namespace,
            @RequestParam(defaultValue = "50") int limit) {
        checkPermission();
        List<Event> eventList = kubernetesClient.v1().events().inNamespace(ns(namespace)).list().getItems();
        List<Map<String, Object>> items = eventList.stream()
                .sorted(Comparator.comparing(e -> e.getLastTimestamp() != null ? e.getLastTimestamp() : "", Comparator.reverseOrder()))
                .limit(limit)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", e.getType());
                    m.put("reason", e.getReason());
                    m.put("message", e.getMessage());
                    m.put("lastTimestamp", e.getLastTimestamp());
                    if (e.getInvolvedObject() != null) {
                        m.put("object", e.getInvolvedObject().getName());
                    }
                    return m;
                }).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("code", 0, "data", items));
    }

    @DeleteMapping("/pods/{podName}")
    public ResponseEntity<Map<String, Object>> deletePod(
            @PathVariable String podName,
            @RequestParam(required = false) String namespace) {
        checkPermission();
        kubernetesClient.pods().inNamespace(ns(namespace)).withName(podName).delete();
        return ResponseEntity.ok(Map.of("code", 0, "message", "Pod " + podName + " deleted"));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurity(SecurityException e) {
        return ResponseEntity.status(403).body(Map.of("code", 40300, "msg", e.getMessage()));
    }

    private Map<String, Object> podToMap(Pod pod) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", pod.getMetadata().getName());
        m.put("namespace", pod.getMetadata().getNamespace());
        m.put("phase", pod.getStatus().getPhase());
        m.put("startTime", pod.getStatus().getStartTime());
        m.put("nodeName", pod.getSpec().getNodeName());
        if (pod.getStatus().getContainerStatuses() != null) {
            m.put("containers", pod.getStatus().getContainerStatuses().stream()
                    .map(cs -> Map.of("name", cs.getName(), "ready", cs.getReady(),
                            "restartCount", cs.getRestartCount()))
                    .collect(Collectors.toList()));
        }
        return m;
    }
}
