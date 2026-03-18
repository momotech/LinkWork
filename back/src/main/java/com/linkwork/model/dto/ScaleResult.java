package com.linkwork.model.dto;

import java.util.List;

public class ScaleResult {

    private String serviceId;
    private boolean success;
    private String scaleType;
    private int previousPodCount;
    private int currentPodCount;
    private int maxPodCount;
    private List<String> runningPods;
    private List<String> addedPods;
    private List<String> removedPods;
    private String errorMessage;

    public static ScaleResult failed(String serviceId, String errorMessage) {
        ScaleResult result = new ScaleResult();
        result.setServiceId(serviceId);
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
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

    public String getScaleType() {
        return scaleType;
    }

    public void setScaleType(String scaleType) {
        this.scaleType = scaleType;
    }

    public int getPreviousPodCount() {
        return previousPodCount;
    }

    public void setPreviousPodCount(int previousPodCount) {
        this.previousPodCount = previousPodCount;
    }

    public int getCurrentPodCount() {
        return currentPodCount;
    }

    public void setCurrentPodCount(int currentPodCount) {
        this.currentPodCount = currentPodCount;
    }

    public int getMaxPodCount() {
        return maxPodCount;
    }

    public void setMaxPodCount(int maxPodCount) {
        this.maxPodCount = maxPodCount;
    }

    public List<String> getRunningPods() {
        return runningPods;
    }

    public void setRunningPods(List<String> runningPods) {
        this.runningPods = runningPods;
    }

    public List<String> getAddedPods() {
        return addedPods;
    }

    public void setAddedPods(List<String> addedPods) {
        this.addedPods = addedPods;
    }

    public List<String> getRemovedPods() {
        return removedPods;
    }

    public void setRemovedPods(List<String> removedPods) {
        this.removedPods = removedPods;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
