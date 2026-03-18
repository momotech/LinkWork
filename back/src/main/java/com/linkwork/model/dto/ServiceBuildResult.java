package com.linkwork.model.dto;

import java.time.Instant;
import java.util.List;

public class ServiceBuildResult {

    private String serviceId;
    private boolean success;
    private String status;
    private String podGroupName;
    private List<String> podNames;
    private String queueName;
    private String scheduledNode;
    private String agentImage;
    private Boolean imagePushed;
    private Long imageBuildDurationMs;
    private Instant createdAt;
    private String errorCode;
    private String errorMessage;

    public static ServiceBuildResult success(String serviceId, String podGroupName, List<String> podNames, String queueName, String scheduledNode) {
        ServiceBuildResult result = new ServiceBuildResult();
        result.setServiceId(serviceId);
        result.setSuccess(true);
        result.setStatus("SUCCESS");
        result.setPodGroupName(podGroupName);
        result.setPodNames(podNames);
        result.setQueueName(queueName);
        result.setScheduledNode(scheduledNode);
        result.setCreatedAt(Instant.now());
        return result;
    }

    public static ServiceBuildResult failed(String serviceId, String errorCode, String errorMessage) {
        ServiceBuildResult result = new ServiceBuildResult();
        result.setServiceId(serviceId);
        result.setSuccess(false);
        result.setStatus("FAILED");
        result.setErrorCode(errorCode);
        result.setErrorMessage(errorMessage);
        result.setCreatedAt(Instant.now());
        return result;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPodGroupName() {
        return podGroupName;
    }

    public void setPodGroupName(String podGroupName) {
        this.podGroupName = podGroupName;
    }

    public List<String> getPodNames() {
        return podNames;
    }

    public void setPodNames(List<String> podNames) {
        this.podNames = podNames;
    }

    public String getQueueName() {
        return queueName;
    }

    public void setQueueName(String queueName) {
        this.queueName = queueName;
    }

    public String getScheduledNode() {
        return scheduledNode;
    }

    public void setScheduledNode(String scheduledNode) {
        this.scheduledNode = scheduledNode;
    }

    public String getAgentImage() {
        return agentImage;
    }

    public void setAgentImage(String agentImage) {
        this.agentImage = agentImage;
    }

    public Boolean getImagePushed() {
        return imagePushed;
    }

    public void setImagePushed(Boolean imagePushed) {
        this.imagePushed = imagePushed;
    }

    public Long getImageBuildDurationMs() {
        return imageBuildDurationMs;
    }

    public void setImageBuildDurationMs(Long imageBuildDurationMs) {
        this.imageBuildDurationMs = imageBuildDurationMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
