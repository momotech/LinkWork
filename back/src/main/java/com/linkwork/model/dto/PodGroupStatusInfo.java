package com.linkwork.model.dto;

public class PodGroupStatusInfo {

    private String name;
    private String phase;
    private Integer minMember;
    private Integer running;
    private Integer succeeded;
    private Integer failed;
    private Integer pending;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public Integer getMinMember() {
        return minMember;
    }

    public void setMinMember(Integer minMember) {
        this.minMember = minMember;
    }

    public Integer getRunning() {
        return running;
    }

    public void setRunning(Integer running) {
        this.running = running;
    }

    public Integer getSucceeded() {
        return succeeded;
    }

    public void setSucceeded(Integer succeeded) {
        this.succeeded = succeeded;
    }

    public Integer getFailed() {
        return failed;
    }

    public void setFailed(Integer failed) {
        this.failed = failed;
    }

    public Integer getPending() {
        return pending;
    }

    public void setPending(Integer pending) {
        this.pending = pending;
    }
}
