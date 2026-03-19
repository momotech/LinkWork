package com.linkwork.model.dto;

public class ImageBuildResult {

    private boolean success;
    private String agentImageTag;
    private String errorMessage;
    private long buildDurationMs;
    private boolean pushed;
    private boolean localLoaded;

    public static ImageBuildResult success(String agentImageTag, long buildDurationMs, boolean pushed) {
        return success(agentImageTag, buildDurationMs, pushed, false);
    }

    public static ImageBuildResult success(String agentImageTag, long buildDurationMs, boolean pushed, boolean localLoaded) {
        ImageBuildResult result = new ImageBuildResult();
        result.setSuccess(true);
        result.setAgentImageTag(agentImageTag);
        result.setBuildDurationMs(buildDurationMs);
        result.setPushed(pushed);
        result.setLocalLoaded(localLoaded);
        return result;
    }

    public static ImageBuildResult failed(String errorMessage) {
        ImageBuildResult result = new ImageBuildResult();
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        return result;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getAgentImageTag() {
        return agentImageTag;
    }

    public void setAgentImageTag(String agentImageTag) {
        this.agentImageTag = agentImageTag;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getBuildDurationMs() {
        return buildDurationMs;
    }

    public void setBuildDurationMs(long buildDurationMs) {
        this.buildDurationMs = buildDurationMs;
    }

    public boolean isPushed() {
        return pushed;
    }

    public void setPushed(boolean pushed) {
        this.pushed = pushed;
    }

    public boolean isLocalLoaded() {
        return localLoaded;
    }

    public void setLocalLoaded(boolean localLoaded) {
        this.localLoaded = localLoaded;
    }
}
