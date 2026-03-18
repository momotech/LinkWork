package com.linkwork.model.dto;

import java.time.Instant;
import java.util.List;

public class ServiceStatusResponse {

    private String serviceId;
    private PodGroupStatusInfo podGroupStatus;
    private List<PodStatusInfo> pods;
    private Instant updatedAt;

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public PodGroupStatusInfo getPodGroupStatus() {
        return podGroupStatus;
    }

    public void setPodGroupStatus(PodGroupStatusInfo podGroupStatus) {
        this.podGroupStatus = podGroupStatus;
    }

    public List<PodStatusInfo> getPods() {
        return pods;
    }

    public void setPods(List<PodStatusInfo> pods) {
        this.pods = pods;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
