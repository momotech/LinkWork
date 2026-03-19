package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkwork.agent.sandbox.core.SandboxOrchestrator;
import com.linkwork.agent.sandbox.core.model.SandboxMode;
import com.linkwork.agent.sandbox.core.model.SandboxNaming;
import com.linkwork.agent.sandbox.core.model.SandboxPodStatus;
import com.linkwork.agent.sandbox.core.model.SandboxPreview;
import com.linkwork.agent.sandbox.core.model.SandboxResult;
import com.linkwork.agent.sandbox.core.model.SandboxScaleResult;
import com.linkwork.agent.sandbox.core.model.SandboxSpec;
import com.linkwork.agent.sandbox.core.model.SandboxStatus;
import com.linkwork.agent.sandbox.core.model.VolumeMountDef;
import com.linkwork.config.ImageBuildProperties;
import com.linkwork.config.NfsStorageConfig;
import com.linkwork.model.dto.GeneratedSpec;
import com.linkwork.model.dto.ImageBuildResult;
import com.linkwork.model.dto.PodGroupStatusInfo;
import com.linkwork.model.dto.PodStatusInfo;
import com.linkwork.model.dto.ScaleRequest;
import com.linkwork.model.dto.ScaleResult;
import com.linkwork.model.dto.ServiceBuildRequest;
import com.linkwork.model.dto.ServiceBuildResult;
import com.linkwork.model.dto.ServiceStatusResponse;
import com.linkwork.model.dto.VolumeMountRequest;
import com.linkwork.model.enums.DeployMode;
import com.linkwork.model.enums.PodMode;
import com.linkwork.model.mcp.McpServerRecord;
import com.linkwork.model.role.RoleRecord;
import com.linkwork.service.mcp.McpServerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SandboxScheduleService {

    private static final Logger log = LoggerFactory.getLogger(SandboxScheduleService.class);
    private static final String DEFAULT_AGENT_CONFIG_PATH = "scripts/config.json";
    private static final String DEFAULT_RUNNER_SCRIPT_PATH = "scripts/start-runner.sh";

    private final SandboxOrchestrator sandboxOrchestrator;
    private final ImageBuildService imageBuildService;
    private final ImageBuildProperties imageBuildProperties;
    private final NfsStorageConfig nfsStorageConfig;
    private final RoleService roleService;
    private final McpServerService mcpServerService;
    private final ObjectMapper objectMapper;
    private volatile String cachedDefaultAgentConfig;
    private volatile String cachedDefaultRunnerScript;

    public SandboxScheduleService(SandboxOrchestrator sandboxOrchestrator,
                                  ImageBuildService imageBuildService,
                                  ImageBuildProperties imageBuildProperties,
                                  NfsStorageConfig nfsStorageConfig,
                                  RoleService roleService,
                                  McpServerService mcpServerService,
                                  ObjectMapper objectMapper) {
        this.sandboxOrchestrator = sandboxOrchestrator;
        this.imageBuildService = imageBuildService;
        this.imageBuildProperties = imageBuildProperties;
        this.nfsStorageConfig = nfsStorageConfig;
        this.roleService = roleService;
        this.mcpServerService = mcpServerService;
        this.objectMapper = objectMapper;
    }

    public ServiceBuildResult build(ServiceBuildRequest request) {
        if (request.getDeployMode() == DeployMode.COMPOSE) {
            return ServiceBuildResult.failed(request.getServiceId(), "UNSUPPORTED_DEPLOY_MODE", "COMPOSE mode is not supported in linkwork-assembly");
        }

        try {
            injectMcpConfig(request);
        } catch (IllegalArgumentException ex) {
            return ServiceBuildResult.failed(request.getServiceId(), "INVALID_MCP_CONFIG", ex.getMessage());
        }

        ImageBuildResult imageBuildResult = null;
        if (shouldBuildImage(request)) {
            imageBuildResult = imageBuildService.buildImages(request);
            if (!imageBuildResult.isSuccess()) {
                return ServiceBuildResult.failed(request.getServiceId(), "IMAGE_BUILD_FAILED", imageBuildResult.getErrorMessage());
            }
            request.setAgentImage(imageBuildResult.getAgentImageTag());
            if (imageBuildResult.isPushed()) {
                if (!StringUtils.hasText(request.getImagePullSecret()) && StringUtils.hasText(imageBuildProperties.getImagePullSecret())) {
                    request.setImagePullSecret(imageBuildProperties.getImagePullSecret());
                }
                if (!StringUtils.hasText(request.getImagePullPolicy())) {
                    request.setImagePullPolicy("Always");
                }
            } else {
                if (!StringUtils.hasText(request.getImagePullPolicy())) {
                    request.setImagePullPolicy("IfNotPresent");
                }
                if (!StringUtils.hasText(request.getImagePullSecret())) {
                    request.setImagePullSecret(null);
                }
            }
        }

        SandboxSpec spec = toSandboxSpec(request);
        SandboxResult result = sandboxOrchestrator.createSandbox(spec);
        if (result.isSuccess()) {
            ServiceBuildResult buildResult = ServiceBuildResult.success(
                request.getServiceId(),
                result.getPodGroupName(),
                result.getPodNames(),
                spec.getQueueName(),
                result.getScheduledNode()
            );
            buildResult.setAgentImage(spec.getAgentImage());
            if (imageBuildResult != null) {
                buildResult.setImageBuildDurationMs(imageBuildResult.getBuildDurationMs());
                buildResult.setImagePushed(imageBuildResult.isPushed());
            }
            return buildResult;
        }
        return ServiceBuildResult.failed(request.getServiceId(), result.getErrorCode(), result.getErrorMessage());
    }

    public GeneratedSpec preview(ServiceBuildRequest request) {
        SandboxSpec spec = toSandboxSpec(request);
        SandboxPreview preview = sandboxOrchestrator.previewSandbox(spec);

        GeneratedSpec generatedSpec = new GeneratedSpec();
        generatedSpec.setServiceId(request.getServiceId());
        generatedSpec.setPodGroupSpec(preview.getPodGroupSpec());
        generatedSpec.setPodSpecs(preview.getPodSpecs());
        return generatedSpec;
    }

    public ServiceStatusResponse status(String serviceId, String namespace) {
        SandboxStatus status = sandboxOrchestrator.querySandbox(serviceId, namespace);

        ServiceStatusResponse response = new ServiceStatusResponse();
        response.setServiceId(serviceId);
        response.setUpdatedAt(Instant.now());

        PodGroupStatusInfo podGroupStatus = new PodGroupStatusInfo();
        podGroupStatus.setName(SandboxNaming.podGroupName(serviceId));
        podGroupStatus.setPhase(status.getPodGroupPhase());
        podGroupStatus.setMinMember(nullSafe(status.getPodGroupMinMember()));
        podGroupStatus.setRunning(nullSafe(status.getPodGroupRunning()));
        podGroupStatus.setSucceeded(nullSafe(status.getPodGroupSucceeded()));
        podGroupStatus.setFailed(nullSafe(status.getPodGroupFailed()));
        podGroupStatus.setPending(nullSafe(status.getPodGroupPending()));
        response.setPodGroupStatus(podGroupStatus);

        List<PodStatusInfo> pods = new ArrayList<>();
        for (SandboxPodStatus pod : status.getPods()) {
            PodStatusInfo podStatusInfo = new PodStatusInfo();
            podStatusInfo.setName(pod.getPodName());
            podStatusInfo.setPhase(pod.getPhase());
            podStatusInfo.setNodeName(pod.getNodeName());
            pods.add(podStatusInfo);
        }
        response.setPods(pods);
        return response;
    }

    public void delete(String serviceId, String namespace) {
        sandboxOrchestrator.destroySandbox(serviceId, namespace);
    }

    public SandboxResult stop(String serviceId, boolean graceful, String namespace) {
        return sandboxOrchestrator.stopSandbox(serviceId, namespace, graceful);
    }

    public ScaleResult scaleDown(String serviceId, ScaleRequest request) {
        String podName = request == null ? null : request.getPodName();
        SandboxScaleResult result = sandboxOrchestrator.scaleDown(serviceId, podName, null);
        return toScaleResult(serviceId, result);
    }

    public ScaleResult scaleUp(String serviceId, ScaleRequest request) {
        if (request == null || request.getTargetPodCount() == null) {
            return ScaleResult.failed(serviceId, "targetPodCount is required for scale-up");
        }
        SandboxScaleResult result = sandboxOrchestrator.scaleUp(serviceId, request.getTargetPodCount(), null, null);
        return toScaleResult(serviceId, result);
    }

    public ScaleResult scale(String serviceId, ScaleRequest request) {
        if (request == null || request.getTargetPodCount() == null) {
            return ScaleResult.failed(serviceId, "targetPodCount is required");
        }
        int current = sandboxOrchestrator.listRunningPods(serviceId, null).size();
        if (request.getTargetPodCount() > current) {
            return scaleUp(serviceId, request);
        }
        if (request.getTargetPodCount() < current) {
            if (!StringUtils.hasText(request.getPodName())) {
                return ScaleResult.failed(serviceId, "podName is required for scale-down");
            }
            return scaleDown(serviceId, request);
        }
        ScaleResult result = new ScaleResult();
        result.setServiceId(serviceId);
        result.setSuccess(true);
        result.setScaleType("NO_CHANGE");
        result.setPreviousPodCount(current);
        result.setCurrentPodCount(current);
        result.setMaxPodCount(current);
        result.setRunningPods(sandboxOrchestrator.listRunningPods(serviceId, null));
        return result;
    }

    public ScaleResult scaleStatus(String serviceId) {
        List<String> runningPods = sandboxOrchestrator.listRunningPods(serviceId, null);
        ScaleResult result = new ScaleResult();
        result.setServiceId(serviceId);
        result.setSuccess(true);
        result.setScaleType("STATUS");
        result.setPreviousPodCount(runningPods.size());
        result.setCurrentPodCount(runningPods.size());
        result.setMaxPodCount(runningPods.size());
        result.setRunningPods(runningPods);
        return result;
    }

    private boolean shouldBuildImage(ServiceBuildRequest request) {
        if (!imageBuildProperties.isEnabled()) {
            return false;
        }
        if (request.getDeployMode() != DeployMode.K8S) {
            return false;
        }
        if (Boolean.TRUE.equals(request.getSkipImageBuild())) {
            return false;
        }
        return !StringUtils.hasText(request.getAgentImage());
    }

    private void injectMcpConfig(ServiceBuildRequest request) {
        if (request == null) {
            return;
        }

        Long roleId = request.getRoleId();
        if (roleId == null) {
            log.debug("No roleId provided, skipping MCP config injection");
            return;
        }

        RoleRecord role = roleService.getById(roleId);
        if (role == null) {
            log.debug("Role not found for roleId: {}, skipping MCP config injection", roleId);
            return;
        }

        List<String> refs = extractRoleMcpRefs(role);
        if (refs.isEmpty()) {
            log.debug("No MCP refs configured for role {}, skipping MCP config injection", roleId);
            return;
        }

        List<Long> ids = resolveMcpIds(roleId, refs);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException(
                String.format("Role [%d] configured MCP refs cannot be resolved: %s", roleId, refs));
        }

        Map<String, Object> mcpConfig = mcpServerService.generateMcpConfig(ids);
        try {
            Map<String, Object> envVars = request.getBuildEnvVars();
            if (envVars == null) {
                envVars = new LinkedHashMap<>();
                request.setBuildEnvVars(envVars);
            }
            envVars.put("MCP_CONFIG", objectMapper.writeValueAsString(mcpConfig));
            log.info("Injected MCP config for roleId: {} ({} servers, resolved from {} refs)",
                roleId, ids.size(), refs.size());
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to serialize MCP_CONFIG: " + ex.getMessage(), ex);
        }
    }

    private List<String> extractRoleMcpRefs(RoleRecord role) {
        if (role == null || role.getConfigJson() == null) {
            return List.of();
        }
        return toRefList(role.getConfigJson().get("mcp"));
    }

    private List<String> toRefList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> refs = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                if (item instanceof Map<?, ?> map) {
                    String id = asString(map.get("id"));
                    String name = asString(map.get("name"));
                    if (StringUtils.hasText(id)) {
                        refs.add(id.trim());
                    } else if (StringUtils.hasText(name)) {
                        refs.add(name.trim());
                    }
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (StringUtils.hasText(text)) {
                    refs.add(text);
                }
            }
            return refs;
        }

        String text = String.valueOf(raw).trim();
        if (StringUtils.hasText(text)) {
            refs.add(text);
        }
        return refs;
    }

    private List<Long> resolveMcpIds(Long roleId, List<String> refs) {
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();

        for (String ref : refs) {
            if (!StringUtils.hasText(ref)) {
                continue;
            }
            try {
                ids.add(Long.parseLong(ref));
            } catch (NumberFormatException ex) {
                names.add(ref);
            }
        }

        if (!names.isEmpty()) {
            LambdaQueryWrapper<McpServerRecord> byName = new LambdaQueryWrapper<>();
            byName.in(McpServerRecord::getName, names);
            List<McpServerRecord> byNames = mcpServerService.list(byName);
            for (McpServerRecord record : byNames) {
                ids.add(record.getId());
            }
            if (byNames.size() < names.size()) {
                List<String> foundNames = byNames.stream().map(McpServerRecord::getName).toList();
                List<String> missingNames = names.stream().filter(name -> !foundNames.contains(name)).toList();
                throw new IllegalArgumentException(
                    String.format("Role [%d] configured MCP names not found: %s", roleId, missingNames));
            }
        }

        return ids.stream().distinct().toList();
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private SandboxSpec toSandboxSpec(ServiceBuildRequest request) {
        SandboxSpec spec = new SandboxSpec();
        spec.setSandboxId(request.getServiceId());
        spec.setNamespace(request.getNamespace());
        spec.setPodCount(request.getPodCount() == null ? 1 : request.getPodCount());
        spec.setMode(request.getPodMode() == PodMode.SIDECAR ? SandboxMode.SIDECAR : SandboxMode.ALONE);
        spec.setPreferredNode(request.getPreferredNode());
        spec.setAgentImage(request.getAgentImage());
        spec.setRunnerImage(request.getRunnerBaseImage());
        spec.setQueueName(request.getQueueName());
        spec.setPriorityClassName(request.getPriorityClassName());
        spec.setImagePullPolicy(request.getImagePullPolicy());
        spec.setImagePullSecret(request.getImagePullSecret());
        spec.setWorkspaceSizeGi(request.getWorkspaceSizeLimit());

        if (request.getPodMode() == PodMode.SIDECAR) {
            spec.setAgentCommand(Collections.singletonList("/opt/agent/start-dual.sh"));
        } else {
            spec.setAgentCommand(Collections.singletonList("/opt/agent/start-single.sh"));
        }

        Map<String, String> injectedEnvs = new LinkedHashMap<>();
        if (request.getInjectedEnvs() != null) {
            injectedEnvs.putAll(request.getInjectedEnvs());
        }
        if (request.getBuildEnvVars() != null) {
            request.getBuildEnvVars().forEach((k, v) -> {
                if (!StringUtils.hasText(k) || v == null) {
                    return;
                }
                injectedEnvs.putIfAbsent(k, String.valueOf(v));
            });
        }
        injectedEnvs.putIfAbsent("WORKSTATION_ID", resolveWorkstationId(request));
        injectedEnvs.putIfAbsent("SERVICE_ID", request.getServiceId());
        injectedEnvs.putIfAbsent("USER_ID", request.getUserId());
        injectedEnvs.putIfAbsent("CONFIG_FILE", "/opt/agent/config.json");
        injectedEnvs.putIfAbsent("IDLE_TIMEOUT", "86400");
        if (request.getPodMode() == PodMode.SIDECAR) {
            injectedEnvs.putIfAbsent("ZZD_RUNNER_HOST", "localhost");
            injectedEnvs.putIfAbsent("ZZD_FORCE_GIT_LOCAL_ROUTE", "true");
        }
        applyRuntimeGitTokenDefaults(injectedEnvs);
        if (request.getRoleId() != null) {
            injectedEnvs.putIfAbsent("ROLE_ID", String.valueOf(request.getRoleId()));
        }
        if (StringUtils.hasText(request.getRoleName())) {
            injectedEnvs.putIfAbsent("ROLE_NAME", request.getRoleName());
        }
        spec.setInjectedEnvs(injectedEnvs);

        List<VolumeMountDef> mountDefs = new ArrayList<>();
        if (request.getMounts() != null) {
            for (VolumeMountRequest mount : request.getMounts()) {
                if (!StringUtils.hasText(mount.getMountPath())) {
                    continue;
                }
                VolumeMountDef mountDef = new VolumeMountDef();
                mountDef.setName(mount.getName());
                mountDef.setHostPath(mount.getHostPath());
                mountDef.setHostPathType(mount.getHostPathType());
                mountDef.setConfigMapName(mount.getConfigMapName());
                mountDef.setConfigMapKey(mount.getConfigMapKey());
                mountDef.setConfigMapDefaultMode(mount.getConfigMapDefaultMode());
                mountDef.setSecretName(mount.getSecretName());
                mountDef.setSecretKey(mount.getSecretKey());
                mountDef.setSecretDefaultMode(mount.getSecretDefaultMode());
                mountDef.setEmptyDir(mount.isEmptyDir());
                mountDef.setEmptyDirMedium(mount.getEmptyDirMedium());
                mountDef.setEmptyDirSizeLimit(mount.getEmptyDirSizeLimit());
                mountDef.setMountPath(mount.getMountPath());
                mountDef.setSubPath(mount.getSubPath());
                mountDef.setReadOnly(mount.isReadOnly());
                mountDef.setMountPropagation(mount.getMountPropagation());
                mountDef.setContainerTargets(mount.getContainerTargets());
                mountDefs.add(mountDef);
            }
        }
        attachDefaultOssMountsIfMissing(request, mountDefs);

        attachManagedRuntimeResources(request, spec, injectedEnvs, mountDefs);
        spec.setMounts(mountDefs);
        return spec;
    }

    private String resolveWorkstationId(ServiceBuildRequest request) {
        if (request == null) {
            return null;
        }
        if (StringUtils.hasText(request.getWorkstationId())) {
            return request.getWorkstationId().trim();
        }
        if (StringUtils.hasText(request.getServiceId())) {
            return request.getServiceId().trim();
        }
        return null;
    }

    private void applyRuntimeGitTokenDefaults(Map<String, String> injectedEnvs) {
        if (injectedEnvs == null) {
            return;
        }
        String apiServerUrl = firstNonBlank(
            injectedEnvs.get("ZZD_API_SERVER_URL"),
            injectedEnvs.get("API_BASE_URL")
        );

        if (StringUtils.hasText(apiServerUrl)) {
            injectedEnvs.putIfAbsent("ZZD_API_SERVER_URL", apiServerUrl);
            injectedEnvs.putIfAbsent("ZZD_ENABLE_GIT_TOKEN", "true");
            return;
        }

        String current = injectedEnvs.get("ZZD_ENABLE_GIT_TOKEN");
        if (!StringUtils.hasText(current)) {
            injectedEnvs.put("ZZD_ENABLE_GIT_TOKEN", "false");
            return;
        }

        if (Boolean.parseBoolean(current)) {
            injectedEnvs.put("ZZD_ENABLE_GIT_TOKEN", "false");
            log.warn("ZZD_ENABLE_GIT_TOKEN=true but no API_BASE_URL/ZZD_API_SERVER_URL provided, fallback to false");
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String toEnvValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }
    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private void attachDefaultOssMountsIfMissing(ServiceBuildRequest request, List<VolumeMountDef> mountDefs) {
        String basePath = nfsStorageConfig == null ? null : nfsStorageConfig.getBasePath();
        if (!StringUtils.hasText(basePath)) {
            basePath = "/mnt/oss/robot-agent-files";
        }
        String workstationId = resolveWorkstationId(request);
        if (!StringUtils.hasText(workstationId)) {
            workstationId = request.getServiceId();
        }
        String normalizedBase = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;

        addHostPathMountIfMissing(
                mountDefs,
                "oss-data",
                normalizedBase + "/system/" + workstationId,
                "/data/oss/robot");
        addHostPathMountIfMissing(
                mountDefs,
                "oss-user-files",
                normalizedBase + "/user-files",
                "/mnt/user-files");
        addHostPathMountIfMissing(
                mountDefs,
                "oss-workstation",
                normalizedBase + "/workstation/" + workstationId,
                "/mnt/workstation");
    }

    private void addHostPathMountIfMissing(List<VolumeMountDef> mountDefs,
                                           String name,
                                           String hostPath,
                                           String mountPath) {
        if (hasMountPath(mountDefs, mountPath)) {
            return;
        }
        VolumeMountDef mountDef = new VolumeMountDef();
        mountDef.setName(name);
        mountDef.setHostPath(hostPath);
        mountDef.setHostPathType("DirectoryOrCreate");
        mountDef.setMountPath(mountPath);
        mountDef.setReadOnly(false);
        mountDef.setMountPropagation("HostToContainer");
        mountDef.setContainerTargets(Collections.singletonList("agent"));
        mountDefs.add(mountDef);
        log.info("Injected default OSS mount: hostPath={}, mountPath={}", hostPath, mountPath);
    }

    private boolean hasMountPath(List<VolumeMountDef> mountDefs, String mountPath) {
        if (mountDefs == null || !StringUtils.hasText(mountPath)) {
            return false;
        }
        for (VolumeMountDef mountDef : mountDefs) {
            if (mountDef != null && mountPath.equals(mountDef.getMountPath())) {
                return true;
            }
        }
        return false;
    }

    private ScaleResult toScaleResult(String serviceId, SandboxScaleResult source) {
        if (source == null) {
            return ScaleResult.failed(serviceId, "scale result is null");
        }
        if (!source.isSuccess()) {
            return ScaleResult.failed(serviceId, source.getErrorMessage());
        }

        ScaleResult result = new ScaleResult();
        result.setServiceId(serviceId);
        result.setSuccess(true);
        result.setScaleType(source.getScaleType());
        result.setPreviousPodCount(source.getPreviousPodCount());
        result.setCurrentPodCount(source.getCurrentPodCount());
        result.setMaxPodCount(source.getTargetPodCount());
        result.setRunningPods(source.getRunningPods());
        result.setAddedPods(source.getAddedPods());
        result.setRemovedPods(source.getRemovedPods());
        return result;
    }

    private void attachManagedRuntimeResources(ServiceBuildRequest request,
                                               SandboxSpec spec,
                                               Map<String, String> injectedEnvs,
                                               List<VolumeMountDef> mountDefs) {
        Map<String, Map<String, String>> configMaps = new LinkedHashMap<>();
        Map<String, Map<String, String>> secrets = new LinkedHashMap<>();

        String agentConfigJson = resolveAgentConfigJson(request);
        if (StringUtils.hasText(agentConfigJson)) {
            String configMapName = "svc-" + request.getServiceId() + "-agent-config";
            configMaps.put(configMapName, Map.of("config.json", agentConfigJson));

            VolumeMountDef configMount = new VolumeMountDef();
            configMount.setName("agent-config");
            configMount.setConfigMapName(configMapName);
            configMount.setConfigMapKey("config.json");
            configMount.setMountPath("/opt/agent/config.json");
            configMount.setSubPath("config.json");
            configMount.setReadOnly(true);
            configMount.setContainerTargets(Collections.singletonList("agent"));
            mountDefs.add(configMount);
            injectedEnvs.putIfAbsent("CONFIG_FILE", "/opt/agent/config.json");
        }

        if (request.getPodMode() == PodMode.SIDECAR) {
            String runnerStartScript = resolveRunnerStartScript(request);
            if (StringUtils.hasText(runnerStartScript)) {
                String configMapName = "svc-" + request.getServiceId() + "-runner-script";
                configMaps.put(configMapName, Map.of("start-runner.sh", runnerStartScript));

                VolumeMountDef scriptMount = new VolumeMountDef();
                scriptMount.setName("runner-script");
                scriptMount.setConfigMapName(configMapName);
                scriptMount.setConfigMapKey("start-runner.sh");
                scriptMount.setConfigMapDefaultMode(493);
                scriptMount.setMountPath("/opt/runner/start-runner.sh");
                scriptMount.setSubPath("start-runner.sh");
                scriptMount.setReadOnly(true);
                scriptMount.setContainerTargets(Collections.singletonList("runner"));
                mountDefs.add(scriptMount);
            }
            spec.setRunnerCommand(Collections.singletonList("/opt/runner/start-runner.sh"));
        }

        if (StringUtils.hasText(request.getRuntimeToken())) {
            String secretName = "svc-" + request.getServiceId() + "-token";
            secrets.put(secretName, Map.of("token", request.getRuntimeToken()));

            VolumeMountDef tokenMount = new VolumeMountDef();
            tokenMount.setName("runtime-token");
            tokenMount.setSecretName(secretName);
            tokenMount.setSecretKey("token");
            tokenMount.setMountPath("/var/run/linkwork/token");
            tokenMount.setSubPath("token");
            tokenMount.setReadOnly(true);
            tokenMount.setContainerTargets(Collections.singletonList("agent"));
            mountDefs.add(tokenMount);
            injectedEnvs.putIfAbsent("LINKWORK_TOKEN_FILE", "/var/run/linkwork/token");
        }

        spec.setConfigMaps(configMaps);
        spec.setSecrets(secrets);
    }

    private String resolveAgentConfigJson(ServiceBuildRequest request) {
        if (StringUtils.hasText(request.getAgentConfigJson())) {
            return request.getAgentConfigJson();
        }
        if (cachedDefaultAgentConfig == null) {
            String raw = loadClasspathResource(DEFAULT_AGENT_CONFIG_PATH, "{}");
            cachedDefaultAgentConfig = applyAnthropicBaseUrl(raw);
        }
        return cachedDefaultAgentConfig;
    }

    private String resolveRunnerStartScript(ServiceBuildRequest request) {
        if (StringUtils.hasText(request.getRunnerStartScript())) {
            return request.getRunnerStartScript();
        }
        if (cachedDefaultRunnerScript == null) {
            cachedDefaultRunnerScript = loadClasspathResource(DEFAULT_RUNNER_SCRIPT_PATH, "#!/bin/bash\nset -e\nexec /usr/sbin/sshd -D -e\n");
        }
        return cachedDefaultRunnerScript;
    }

    private String loadClasspathResource(String path, String fallbackValue) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                log.warn("Classpath resource not found: {}, fallback will be used", path);
                return fallbackValue;
            }
            return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));
        } catch (Exception ex) {
            log.warn("Failed to load classpath resource {}, fallback will be used: {}", path, ex.getMessage());
            return fallbackValue;
        }
    }

    private String applyAnthropicBaseUrl(String rawConfig) {
        if (!StringUtils.hasText(rawConfig)) {
            return "{}";
        }
        String anthropicBaseUrl = imageBuildProperties == null ? null : imageBuildProperties.getAnthropicBaseUrl();
        String anthropicAuthToken = imageBuildProperties == null ? null : imageBuildProperties.getAnthropicAuthToken();
        String anthropicApiKey = imageBuildProperties == null ? null : imageBuildProperties.getAnthropicApiKey();
        if (!StringUtils.hasText(anthropicApiKey) && StringUtils.hasText(anthropicAuthToken)) {
            anthropicApiKey = anthropicAuthToken;
        }
        if (!StringUtils.hasText(anthropicBaseUrl)
            && !StringUtils.hasText(anthropicAuthToken)
            && !StringUtils.hasText(anthropicApiKey)) {
            return rawConfig;
        }
        try {
            JsonNode root = objectMapper.readTree(rawConfig);
            if (!(root instanceof ObjectNode rootObj)) {
                return rawConfig;
            }
            ObjectNode claudeSettings = ensureObjectNode(rootObj, "claude_settings");
            ObjectNode envNode = ensureObjectNode(claudeSettings, "env");
            if (StringUtils.hasText(anthropicBaseUrl)) {
                envNode.put("ANTHROPIC_BASE_URL", anthropicBaseUrl.trim());
            }
            if (StringUtils.hasText(anthropicAuthToken)) {
                envNode.put("ANTHROPIC_AUTH_TOKEN", anthropicAuthToken.trim());
            }
            if (StringUtils.hasText(anthropicApiKey)) {
                envNode.put("ANTHROPIC_API_KEY", anthropicApiKey.trim());
            }
            return objectMapper.writeValueAsString(rootObj);
        } catch (Exception ex) {
            log.warn("Failed to apply anthropic base url in default agent config: {}", ex.getMessage());
            return rawConfig;
        }
    }

    private ObjectNode ensureObjectNode(ObjectNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        ObjectNode created = objectMapper.createObjectNode();
        parent.set(fieldName, created);
        return created;
    }
}
