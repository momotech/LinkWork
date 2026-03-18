package com.linkwork.model.dto;

import java.util.ArrayList;
import java.util.List;

public class VolumeMountRequest {

    private String name;
    private String hostPath;
    private String hostPathType;
    private String configMapName;
    private String configMapKey;
    private Integer configMapDefaultMode;
    private String secretName;
    private String secretKey;
    private Integer secretDefaultMode;
    private boolean emptyDir;
    private String emptyDirMedium;
    private String emptyDirSizeLimit;
    private String mountPath;
    private String subPath;
    private boolean readOnly;
    private String mountPropagation;
    private List<String> containerTargets = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHostPath() {
        return hostPath;
    }

    public void setHostPath(String hostPath) {
        this.hostPath = hostPath;
    }

    public String getHostPathType() {
        return hostPathType;
    }

    public void setHostPathType(String hostPathType) {
        this.hostPathType = hostPathType;
    }

    public String getConfigMapName() {
        return configMapName;
    }

    public void setConfigMapName(String configMapName) {
        this.configMapName = configMapName;
    }

    public String getConfigMapKey() {
        return configMapKey;
    }

    public void setConfigMapKey(String configMapKey) {
        this.configMapKey = configMapKey;
    }

    public Integer getConfigMapDefaultMode() {
        return configMapDefaultMode;
    }

    public void setConfigMapDefaultMode(Integer configMapDefaultMode) {
        this.configMapDefaultMode = configMapDefaultMode;
    }

    public String getSecretName() {
        return secretName;
    }

    public void setSecretName(String secretName) {
        this.secretName = secretName;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public Integer getSecretDefaultMode() {
        return secretDefaultMode;
    }

    public void setSecretDefaultMode(Integer secretDefaultMode) {
        this.secretDefaultMode = secretDefaultMode;
    }

    public boolean isEmptyDir() {
        return emptyDir;
    }

    public void setEmptyDir(boolean emptyDir) {
        this.emptyDir = emptyDir;
    }

    public String getEmptyDirMedium() {
        return emptyDirMedium;
    }

    public void setEmptyDirMedium(String emptyDirMedium) {
        this.emptyDirMedium = emptyDirMedium;
    }

    public String getEmptyDirSizeLimit() {
        return emptyDirSizeLimit;
    }

    public void setEmptyDirSizeLimit(String emptyDirSizeLimit) {
        this.emptyDirSizeLimit = emptyDirSizeLimit;
    }

    public String getMountPath() {
        return mountPath;
    }

    public void setMountPath(String mountPath) {
        this.mountPath = mountPath;
    }

    public String getSubPath() {
        return subPath;
    }

    public void setSubPath(String subPath) {
        this.subPath = subPath;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public String getMountPropagation() {
        return mountPropagation;
    }

    public void setMountPropagation(String mountPropagation) {
        this.mountPropagation = mountPropagation;
    }

    public List<String> getContainerTargets() {
        return containerTargets;
    }

    public void setContainerTargets(List<String> containerTargets) {
        this.containerTargets = containerTargets;
    }
}
