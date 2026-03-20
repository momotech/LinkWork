package com.linkwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 镜像构建配置
 * 
 * 设计说明：
 * - 仅构建 Agent 镜像，Runner 由运行时 agent 启动
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "image-build")
public class ImageBuildConfig {
    
    /**
     * 是否启用镜像构建
     */
    private boolean enabled = false;
    
    /**
     * 是否推送镜像到仓库（K8s 模式）
     * 设置为 false 时只构建不推送，适用于测试环境
     */
    private boolean pushEnabled = false;
    
    /**
     * 镜像拉取策略
     * - Always: 总是拉取（默认，需要镜像在仓库中）
     * - IfNotPresent: 本地有则不拉取（适合本地构建 + 单节点/共享 Docker）
     * - Never: 从不拉取（要求镜像必须已在节点上）
     */
    private String imagePullPolicy = "IfNotPresent";
    
    /**
     * K8s 拉取私有镜像的 Secret 名称
     * 需要在 K8s 中预先创建，或由服务自动创建
     */
    private String imagePullSecret = "robot-registry-secret";
    
    /**
     * Docker 连接配置
     * 默认使用 unix socket: unix:///var/run/docker.sock
     */
    private String dockerHost = "unix:///var/run/docker.sock";
    
    /**
     * 默认 Agent 基础镜像（K8s 模式构建使用内网 Harbor）
     */
    private String defaultAgentBaseImage = "10.30.107.146/robot/rockylinux9-agent@sha256:b49d75f52f6b3c55bbf90427f0df0e97bc8e3f3e03727721cafc2c9d775b8975";

    /**
     * Compose 模式基础镜像（用户本地构建，需要可公开拉取的镜像）
     */
    private String composeBaseImage = "rockylinux:9";
    
    /**
     * 镜像仓库地址
     * K8s 模式下构建的镜像会推送到此仓库
     */
    private String registry = "";
    
    /**
     * 镜像仓库用户名
     */
    private String registryUsername = "";
    
    /**
     * 镜像仓库密码
     */
    private String registryPassword = "";
    
    /**
     * 构建脚本路径
     * 此脚本会在 Dockerfile 中被 COPY 并执行
     */
    private String buildScriptPath = "/opt/scripts/build.sh";
    
    /**
     * 构建超时时间（秒）
     */
    private int buildTimeout = 300;
    
    /**
     * 入口点脚本名称
     */
    private String entrypointScript = "/entrypoint.sh";
    
    /**
     * 构建上下文临时目录
     */
    private String buildContextDir = "/tmp/docker-build";
    
    /**
     * SDK 仓库 HTTPS 地址（用于镜像构建阶段克隆）
     */
    private String sdkRepoUrl = "";
    
    /**
     * SDK 仓库克隆分支（默认 test122）
     */
    private String sdkRepoBranch = "test122";

    /**
     * SDK 仓库克隆用户名
     */
    private String sdkRepoUsername = "";
    
    /**
     * SDK 仓库克隆密码
     */
    private String sdkRepoPassword = "";
    
    /**
     * SDK 源码在镜像中的目标路径
     * 从 SDK 仓库的 momo-agent-sdk/ 目录拷贝
     */
    private String sdkSourcePath = "/opt/momo-agent-build/sdk-source";
    
    /**
     * zzd 二进制文件在镜像中的目标路径
     * 从 SDK 仓库的 docker/agent/zzd/ 目录拷贝
     */
    private String zzdBinariesPath = "/opt/momo-agent-build/zzd-binaries";
}
