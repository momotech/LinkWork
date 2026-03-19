package com.linkwork.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "linkwork.build")
public class ImageBuildProperties {

    private boolean enabled = true;
    private boolean pushEnabled = true;
    private boolean localLoadEnabled = false;
    private boolean cleanupLocalAfterPush = true;
    private int buildTimeoutSeconds = 1800;
    private int localLoadTimeoutSeconds = 900;
    private String buildContextDir = "/tmp/linkwork-build";
    private String buildScriptPath;
    private String defaultAgentBaseImage = "ghcr.io/linkwork/agent-base:latest";
    private String registry = "registry.example.com/linkwork";
    private String registryUsername;
    private String registryPassword;
    private String imagePullSecret = "linkwork-registry-secret";
    private String dockerHost;
    private String entrypointScript = "/opt/agent/start-single.sh";
    private String kindClusterName = "kind";
    private String sdkRepoUrl;
    private String sdkRepoBranch = "main";
    private String sdkRepoUsername;
    private String sdkRepoPassword;
    private String anthropicBaseUrl = "http://172.18.228.32:4000/";
    private String anthropicAuthToken;
    private String anthropicApiKey;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isPushEnabled() {
        return pushEnabled;
    }

    public void setPushEnabled(boolean pushEnabled) {
        this.pushEnabled = pushEnabled;
    }

    public boolean isLocalLoadEnabled() {
        return localLoadEnabled;
    }

    public void setLocalLoadEnabled(boolean localLoadEnabled) {
        this.localLoadEnabled = localLoadEnabled;
    }

    public boolean isCleanupLocalAfterPush() {
        return cleanupLocalAfterPush;
    }

    public void setCleanupLocalAfterPush(boolean cleanupLocalAfterPush) {
        this.cleanupLocalAfterPush = cleanupLocalAfterPush;
    }

    public int getBuildTimeoutSeconds() {
        return buildTimeoutSeconds;
    }

    public void setBuildTimeoutSeconds(int buildTimeoutSeconds) {
        this.buildTimeoutSeconds = buildTimeoutSeconds;
    }

    public int getLocalLoadTimeoutSeconds() {
        return localLoadTimeoutSeconds;
    }

    public void setLocalLoadTimeoutSeconds(int localLoadTimeoutSeconds) {
        this.localLoadTimeoutSeconds = localLoadTimeoutSeconds;
    }

    public String getBuildContextDir() {
        return buildContextDir;
    }

    public void setBuildContextDir(String buildContextDir) {
        this.buildContextDir = buildContextDir;
    }

    public String getBuildScriptPath() {
        return buildScriptPath;
    }

    public void setBuildScriptPath(String buildScriptPath) {
        this.buildScriptPath = buildScriptPath;
    }

    public String getDefaultAgentBaseImage() {
        return defaultAgentBaseImage;
    }

    public void setDefaultAgentBaseImage(String defaultAgentBaseImage) {
        this.defaultAgentBaseImage = defaultAgentBaseImage;
    }

    public String getRegistry() {
        return registry;
    }

    public void setRegistry(String registry) {
        this.registry = registry;
    }

    public String getRegistryUsername() {
        return registryUsername;
    }

    public void setRegistryUsername(String registryUsername) {
        this.registryUsername = registryUsername;
    }

    public String getRegistryPassword() {
        return registryPassword;
    }

    public void setRegistryPassword(String registryPassword) {
        this.registryPassword = registryPassword;
    }

    public String getDockerHost() {
        return dockerHost;
    }

    public void setDockerHost(String dockerHost) {
        this.dockerHost = dockerHost;
    }

    public String getImagePullSecret() {
        return imagePullSecret;
    }

    public void setImagePullSecret(String imagePullSecret) {
        this.imagePullSecret = imagePullSecret;
    }

    public String getEntrypointScript() {
        return entrypointScript;
    }

    public void setEntrypointScript(String entrypointScript) {
        this.entrypointScript = entrypointScript;
    }

    public String getKindClusterName() {
        return kindClusterName;
    }

    public void setKindClusterName(String kindClusterName) {
        this.kindClusterName = kindClusterName;
    }

    public String getSdkRepoUrl() {
        return sdkRepoUrl;
    }

    public void setSdkRepoUrl(String sdkRepoUrl) {
        this.sdkRepoUrl = sdkRepoUrl;
    }

    public String getSdkRepoBranch() {
        return sdkRepoBranch;
    }

    public void setSdkRepoBranch(String sdkRepoBranch) {
        this.sdkRepoBranch = sdkRepoBranch;
    }

    public String getSdkRepoUsername() {
        return sdkRepoUsername;
    }

    public void setSdkRepoUsername(String sdkRepoUsername) {
        this.sdkRepoUsername = sdkRepoUsername;
    }

    public String getSdkRepoPassword() {
        return sdkRepoPassword;
    }

    public void setSdkRepoPassword(String sdkRepoPassword) {
        this.sdkRepoPassword = sdkRepoPassword;
    }

    public String getAnthropicBaseUrl() {
        return anthropicBaseUrl;
    }

    public void setAnthropicBaseUrl(String anthropicBaseUrl) {
        this.anthropicBaseUrl = anthropicBaseUrl;
    }

    public String getAnthropicAuthToken() {
        return anthropicAuthToken;
    }

    public void setAnthropicAuthToken(String anthropicAuthToken) {
        this.anthropicAuthToken = anthropicAuthToken;
    }

    public String getAnthropicApiKey() {
        return anthropicApiKey;
    }

    public void setAnthropicApiKey(String anthropicApiKey) {
        this.anthropicApiKey = anthropicApiKey;
    }
}
