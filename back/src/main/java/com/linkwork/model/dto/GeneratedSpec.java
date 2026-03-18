package com.linkwork.model.dto;

import java.util.List;
import java.util.Map;

public class GeneratedSpec {

    private String serviceId;
    private Map<String, Object> podGroupSpec;
    private List<Map<String, Object>> podSpecs;

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public Map<String, Object> getPodGroupSpec() {
        return podGroupSpec;
    }

    public void setPodGroupSpec(Map<String, Object> podGroupSpec) {
        this.podGroupSpec = podGroupSpec;
    }

    public List<Map<String, Object>> getPodSpecs() {
        return podSpecs;
    }

    public void setPodSpecs(List<Map<String, Object>> podSpecs) {
        this.podSpecs = podSpecs;
    }
}
