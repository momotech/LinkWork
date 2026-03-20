package com.linkwork.model.enums;

/**
 * 容器日志事件类型枚举
 * 遵循 data-format.md 规范定义
 */
public enum ContainerEventType {
    
    // ==================== 调度阶段 ====================
    
    /**
     * Pod 正在等待调度
     */
    POD_SCHEDULING,
    
    /**
     * Pod 已调度到节点
     */
    POD_SCHEDULED,
    
    // ==================== 镜像阶段 ====================
    
    /**
     * 正在拉取容器镜像
     */
    IMAGE_PULLING,
    
    /**
     * 镜像拉取完成
     */
    IMAGE_PULLED,
    
    // ==================== 启动阶段 ====================
    
    /**
     * 容器正在启动
     */
    CONTAINER_STARTING,
    
    /**
     * 容器就绪（健康检查通过）
     */
    CONTAINER_READY,
    
    // ==================== 环境阶段 ====================
    
    /**
     * 环境初始化步骤
     */
    ENV_SETUP,
    
    /**
     * 工作区初始化
     */
    WORKSPACE_INIT,
    
    // ==================== 完成阶段 ====================
    
    /**
     * 全部初始化完成
     */
    INIT_COMPLETE,
    
    /**
     * 初始化失败
     */
    INIT_FAILED,
    
    // ==================== 生命周期事件 ====================
    
    /**
     * 会话开始（Agent 启动）
     */
    SESSION_START,
    
    /**
     * 会话结束（服务停止）
     */
    SESSION_END,
    
    // ==================== 镜像构建阶段 ====================
    
    /**
     * 构建开始
     */
    BUILD_STARTED,
    
    /**
     * 构建进度（阶段性状态）
     */
    BUILD_PROGRESS,
    
    /**
     * 构建日志输出（Docker 实时日志行）
     */
    BUILD_LOG,
    
    /**
     * 构建成功完成
     */
    BUILD_COMPLETED,
    
    /**
     * 构建失败
     */
    BUILD_FAILED,
    
    /**
     * 正在推送镜像到仓库
     */
    BUILD_PUSHING,
    
    /**
     * 镜像推送完成
     */
    BUILD_PUSHED
}
