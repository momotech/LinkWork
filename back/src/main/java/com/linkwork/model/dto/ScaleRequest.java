package com.linkwork.model.dto;

public class ScaleRequest {

    private Integer targetPodCount;
    private String podName;
    private String source;

    public Integer getTargetPodCount() {
        return targetPodCount;
    }

    public void setTargetPodCount(Integer targetPodCount) {
        this.targetPodCount = targetPodCount;
    }

    public String getPodName() {
        return podName;
    }

    public void setPodName(String podName) {
        this.podName = podName;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
