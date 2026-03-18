package com.linkwork.model.dto;

import com.linkwork.model.enums.DeployMode;
import com.linkwork.model.enums.PodMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ServiceBuildRequest {

    @NotBlank(message = "serviceId is required")
    private String serviceId;

    @NotBlank(message = "userId is required")
    private String userId;

    private String buildId;
    private Long roleId;
    private String roleName;
    private String workstationId;
    private String description;

    @NotNull(message = "deployMode is required")
    private DeployMode deployMode;

    @NotEmpty(message = "buildEnvVars is required")
    private Map<String, Object> buildEnvVars = new LinkedHashMap<>();

    private PodMode podMode = PodMode.ALONE;
    private Integer podCount = 1;
    private Integer workspaceSizeLimit = 20;
    private String preferredNode;

    private String agentImage;
    private String agentBaseImage;
    private String runnerBaseImage;
    private String imageRegistry;
    private String imagePullPolicy;
    private String imagePullSecret;
    private Boolean skipImageBuild = false;
    private String runtimeToken;
    private String agentConfigJson;
    private String runnerStartScript;
    private String namespace;
    private String queueName;
    private String priorityClassName;

    private List<String> mcpRefs = new ArrayList<>();
    private Map<String, String> injectedEnvs = new LinkedHashMap<>();
    private List<VolumeMountRequest> mounts = new ArrayList<>();

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getBuildId() {
        return buildId;
    }

    public void setBuildId(String buildId) {
        this.buildId = buildId;
    }

    public Long getRoleId() {
        return roleId;
    }

    public void setRoleId(Long roleId) {
        this.roleId = roleId;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getWorkstationId() {
        return workstationId;
    }

    public void setWorkstationId(String workstationId) {
        this.workstationId = workstationId;
    }

    public DeployMode getDeployMode() {
        return deployMode;
    }

    public void setDeployMode(DeployMode deployMode) {
        this.deployMode = deployMode;
    }

    public Map<String, Object> getBuildEnvVars() {
        return buildEnvVars;
    }

    public void setBuildEnvVars(Map<String, Object> buildEnvVars) {
        this.buildEnvVars = buildEnvVars;
    }

    public PodMode getPodMode() {
        return podMode;
    }

    public void setPodMode(PodMode podMode) {
        this.podMode = podMode;
    }

    public Integer getPodCount() {
        return podCount;
    }

    public void setPodCount(Integer podCount) {
        this.podCount = podCount;
    }

    public Integer getWorkspaceSizeLimit() {
        return workspaceSizeLimit;
    }

    public void setWorkspaceSizeLimit(Integer workspaceSizeLimit) {
        this.workspaceSizeLimit = workspaceSizeLimit;
    }

    public String getPreferredNode() {
        return preferredNode;
    }

    public void setPreferredNode(String preferredNode) {
        this.preferredNode = preferredNode;
    }

    public String getAgentImage() {
        return agentImage;
    }

    public void setAgentImage(String agentImage) {
        this.agentImage = agentImage;
    }

    public String getRunnerBaseImage() {
        return runnerBaseImage;
    }

    public void setRunnerBaseImage(String runnerBaseImage) {
        this.runnerBaseImage = runnerBaseImage;
    }

    public String getAgentBaseImage() {
        return agentBaseImage;
    }

    public void setAgentBaseImage(String agentBaseImage) {
        this.agentBaseImage = agentBaseImage;
    }

    public String getImageRegistry() {
        return imageRegistry;
    }

    public void setImageRegistry(String imageRegistry) {
        this.imageRegistry = imageRegistry;
    }

    public String getImagePullPolicy() {
        return imagePullPolicy;
    }

    public void setImagePullPolicy(String imagePullPolicy) {
        this.imagePullPolicy = imagePullPolicy;
    }

    public String getImagePullSecret() {
        return imagePullSecret;
    }

    public void setImagePullSecret(String imagePullSecret) {
        this.imagePullSecret = imagePullSecret;
    }

    public Boolean getSkipImageBuild() {
        return skipImageBuild;
    }

    public void setSkipImageBuild(Boolean skipImageBuild) {
        this.skipImageBuild = skipImageBuild;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getRuntimeToken() {
        return runtimeToken;
    }

    public void setRuntimeToken(String runtimeToken) {
        this.runtimeToken = runtimeToken;
    }

    public String getAgentConfigJson() {
        return agentConfigJson;
    }

    public void setAgentConfigJson(String agentConfigJson) {
        this.agentConfigJson = agentConfigJson;
    }

    public String getRunnerStartScript() {
        return runnerStartScript;
    }

    public void setRunnerStartScript(String runnerStartScript) {
        this.runnerStartScript = runnerStartScript;
    }

    public String getQueueName() {
        return queueName;
    }

    public void setQueueName(String queueName) {
        this.queueName = queueName;
    }

    public String getPriorityClassName() {
        return priorityClassName;
    }

    public void setPriorityClassName(String priorityClassName) {
        this.priorityClassName = priorityClassName;
    }

    public List<String> getMcpRefs() {
        return mcpRefs;
    }

    public void setMcpRefs(List<String> mcpRefs) {
        this.mcpRefs = mcpRefs;
    }

    public Map<String, String> getInjectedEnvs() {
        return injectedEnvs;
    }

    public void setInjectedEnvs(Map<String, String> injectedEnvs) {
        this.injectedEnvs = injectedEnvs;
    }

    public List<VolumeMountRequest> getMounts() {
        return mounts;
    }

    public void setMounts(List<VolumeMountRequest> mounts) {
        this.mounts = mounts;
    }
}
