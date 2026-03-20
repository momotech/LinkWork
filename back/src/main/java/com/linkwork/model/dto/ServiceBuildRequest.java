package com.linkwork.model.dto;

import com.linkwork.model.enums.DeployMode;
import com.linkwork.model.enums.PodMode;
import com.linkwork.model.enums.ServiceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 服务构建请求（Build 服务输入）
 * 
 * 设计说明：
 * - Build 服务会根据传入的环境变量实时构建镜像
 * - 构建流程：拉取基础镜像 → 注入环境变量 → 执行 build.sh → 推送镜像仓库
 * - K8s 模式：构建后推送到镜像仓库，然后创建 PodGroup/Pod
 * - Compose 模式：本地构建，返回 docker-compose.yaml
 * - token 写入容器后仅 executor 用户可访问
 * - ssh-key 在容器启动时生成，agent 和 executor 都可访问
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceBuildRequest {
    
    // ========== 服务信息（必填）==========
    @NotBlank
    private String serviceId;           // 服务唯一标识
    
    @NotBlank
    private String userId;              // 用户 ID
    
    // ========== 构建追踪（可选，由前端生成）==========
    /**
     * 构建唯一标识，由前端生成 (UUID 格式)
     * 用于关联构建记录和 Redis Stream 事件
     */
    private String buildId;
    
    /**
     * 关联的岗位 ID
     */
    private Long roleId;
    
    /**
     * 岗位名称（用于构建记录快照）
     */
    private String roleName;
    
    // ========== 服务信息（可选）==========
    private String description;         // 服务描述（可选）
    
    private ServiceType serviceType;    // CODE_WRITING / TASK_EXECUTION（可选）
    
    // ========== 部署配置（必填）==========
    @NotNull
    private DeployMode deployMode;      // K8S / COMPOSE
    
    // ========== 镜像构建配置（必填）==========
    /**
     * 环境变量列表（必填）- 用于镜像构建
     * 这些环境变量会被写入 Dockerfile 的 ENV 指令，并在 build.sh 中使用
     * 注意：token 也应放入此 Map 中，在 build.sh 执行前会 export
     */
    @NotEmpty
    private Map<String, Object> buildEnvVars;
    
    // ========== 镜像配置（可选）==========
    /**
     * Runner 基础镜像地址（Sidecar 模式使用）
     * K8s + Sidecar 模式下，Runner 容器使用此镜像
     * 为空时使用系统默认配置：schedule.images.runner
     * 注意：非 K8s 模式或 Alone 模式不使用此参数
     */
    private String runnerBaseImage;
    
    /**
     * 镜像仓库地址（可选）
     * 为空时仅本地构建，不推送远端仓库
     * K8s 模式下构建的镜像会推送到此仓库
     */
    private String imageRegistry;
    
    // ========== 可选配置 ==========
    private PodMode podMode;            // SIDECAR / ALONE，为空时使用默认值
    private Integer podCount;           // Pod 数量，默认 1，最大 10（仅 K8s）
    private Integer priority;           // 0-100，默认 50
    private ResourceConfig resourceConfig;  // 自定义资源配置
    
    // ========== 工作目录配置 ==========
    private Integer workspaceSizeLimit; // GB，默认 10
    
    // ========== 回调配置（仅 K8s）==========
    private String callbackUrl;         // 状态回调 URL
    
    // ========== 快速恢复相关（Resume 时使用）==========
    /**
     * 优先调度节点（Resume 时设置）
     * 设置后会在 Pod Spec 中添加节点亲和性，优先调度到原节点以命中镜像缓存
     */
    private String preferredNode;
}
