package com.linkwork.model.enums;

/**
 * Pod 模式
 */
public enum PodMode {
    SIDECAR,  // 双容器：Agent + Runner
    ALONE     // 单容器：三合一
}
