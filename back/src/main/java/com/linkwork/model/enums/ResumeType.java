package com.linkwork.model.enums;

/**
 * 恢复类型
 */
public enum ResumeType {
    /**
     * 温启动：命中镜像缓存，~20s
     */
    WARM,
    
    /**
     * 冷启动：未命中，需重新拉取镜像，~90s
     */
    COLD
}
