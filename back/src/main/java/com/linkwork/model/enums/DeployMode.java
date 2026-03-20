package com.linkwork.model.enums;

/**
 * 部署模式
 */
public enum DeployMode {
    K8S,      // Kubernetes 部署
    COMPOSE   // Docker Compose 部署（仅支持 Alone 模式）
}
