package com.linkwork.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.BuildQueueConfig;
import com.linkwork.config.ImageBuildConfig;
import com.linkwork.model.dto.BuildTask;
import com.linkwork.model.dto.GeneratedSpec;
import com.linkwork.model.dto.ImageBuildResult;
import com.linkwork.model.dto.MergedConfig;
import com.linkwork.model.dto.ServiceBuildRequest;
import com.linkwork.model.dto.ServiceBuildResult;
import com.linkwork.model.entity.McpServerEntity;
import com.linkwork.model.entity.RoleEntity;
import com.linkwork.model.entity.SkillEntity;
import com.linkwork.model.enums.DeployMode;
import com.linkwork.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 服务调度服务
 */
@Service
@Slf4j
public class ServiceScheduleService {
    
    private final ConfigMergeService configMergeService;
    private final K8sOrchestrator orchestrator;
    private final DockerComposeGenerator composeGenerator;
    private final ImageBuildService imageBuildService;
    private final ImageBuildConfig imageBuildConfig;
    private final BuildRecordService buildRecordService;
    private final ScheduleEventPublisher eventPublisher;
    private final GitLabAuthService gitLabAuthService;
    private final BuildQueueService buildQueueService;
    private final BuildQueueConfig buildQueueConfig;
    private final McpServerService mcpServerService;
    private final SkillService skillService;
    private final RoleService roleService;
    private final ObjectMapper objectMapper;
    private final ServiceSnapshotService snapshotService;
    
    @Value("${robot.skills.repo-url:}")
    private String skillsRepoUrl;
    
    @Value("${robot.skills.deploy-token:}")
    private String skillsDeployToken;
    
    public ServiceScheduleService(ConfigMergeService configMergeService,
                                  K8sOrchestrator orchestrator,
                                  DockerComposeGenerator composeGenerator,
                                  ImageBuildService imageBuildService,
                                  ImageBuildConfig imageBuildConfig,
                                  BuildRecordService buildRecordService,
                                  ScheduleEventPublisher eventPublisher,
                                  GitLabAuthService gitLabAuthService,
                                  BuildQueueService buildQueueService,
                                  BuildQueueConfig buildQueueConfig,
                                  McpServerService mcpServerService,
                                  SkillService skillService,
                                  RoleService roleService,
                                  ObjectMapper objectMapper,
                                  ServiceSnapshotService snapshotService) {
        this.configMergeService = configMergeService;
        this.orchestrator = orchestrator;
        this.composeGenerator = composeGenerator;
        this.imageBuildService = imageBuildService;
        this.imageBuildConfig = imageBuildConfig;
        this.buildRecordService = buildRecordService;
        this.eventPublisher = eventPublisher;
        this.gitLabAuthService = gitLabAuthService;
        this.buildQueueService = buildQueueService;
        this.buildQueueConfig = buildQueueConfig;
        this.mcpServerService = mcpServerService;
        this.skillService = skillService;
        this.roleService = roleService;
        this.objectMapper = objectMapper;
        this.snapshotService = snapshotService;
    }
    
    /**
     * 构建服务（同步方式，立即返回，实际构建异步执行）
     * 
     * 如果启用了构建队列，任务会进入队列排队；
     * 否则直接异步执行（兼容旧行为）。
     */
    public ServiceBuildResult build(ServiceBuildRequest request) {
        String serviceId = request.getServiceId();
        
        // 确保 buildId 存在（如果前端未传，则后端生成）
        String buildId = request.getBuildId();
        if (!StringUtils.hasText(buildId)) {
            buildId = UUID.randomUUID().toString();
            request.setBuildId(buildId);
        }
        
        try {
            // 0. 自动注入 GitLab token（从用户绑定的 GitLab 账户获取）
            injectGitLabToken(request);
            
            // 0.5 自动注入 MCP 配置（从岗位 configJson.mcp 生成）
            injectMcpConfig(request);
            
            // 0.6 自动注入 Skills 配置（从岗位 configJson.skills 生成）
            injectSkillsConfig(request);
            
            // 1. 配置融合（快速验证）
            MergedConfig config = configMergeService.merge(request);
            
            // 2. 计算 PodGroup 名称（提前返回给调用方）
            String podGroupName = "svc-" + serviceId + "-pg";
            String queueName = config.getQueueName();
            
            // 3. 创建构建记录
            if (request.getRoleId() != null) {
                Map<String, Object> configSnapshot = createConfigSnapshot(request, config);
                buildRecordService.createBuildRecord(
                    buildId,
                    request.getRoleId(),
                    request.getRoleName(),
                    configSnapshot,
                    request.getUserId(),
                    UserContext.getCurrentUserName()
                );
            }
            
            // 4. 提交构建任务
            if (buildQueueConfig.isEnabled()) {
                // 使用构建队列
                try {
                    BuildTask task = buildQueueService.submit(request, config);
                    int queuePosition = buildQueueService.getWaitingCount();
                    log.info("Build task queued: serviceId={}, buildId={}, queuePosition={}", 
                        serviceId, buildId, queuePosition);
                    
                    String message = queuePosition > 1 
                        ? String.format("Task queued at position %d, waiting for resources", queuePosition)
                        : "Task submitted, starting build...";
                    
                    return ServiceBuildResult.building(serviceId, buildId, podGroupName, queueName, message);
                } catch (IllegalStateException e) {
                    // 队列已满
                    log.warn("Build queue full, rejecting request: serviceId={}", serviceId);
                    return ServiceBuildResult.failed(serviceId, "QUEUE_FULL", e.getMessage());
                }
            } else {
                // 直接异步执行（兼容旧行为）
                buildAsync(request, config);
                log.info("Build task submitted (direct async): serviceId={}, buildId={}", serviceId, buildId);
                return ServiceBuildResult.building(serviceId, buildId, podGroupName, queueName,
                    "Task submitted, starting build...");
            }
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid request for service {}: {}", serviceId, e.getMessage());
            // 更新构建记录为失败
            if (request.getRoleId() != null) {
                buildRecordService.markFailed(buildId, e.getMessage(), 0L);
            }
            return ServiceBuildResult.failed(serviceId, "INVALID_REQUEST", e.getMessage());
        } catch (Exception e) {
            log.error("Failed to submit build task for service {}: {}", serviceId, e.getMessage(), e);
            // 更新构建记录为失败
            if (request.getRoleId() != null) {
                buildRecordService.markFailed(buildId, e.getMessage(), 0L);
            }
            return ServiceBuildResult.failed(serviceId, "INTERNAL_ERROR", e.getMessage());
        }
    }
    
    /**
     * 自动注入 GitLab token 到 buildEnvVars
     * 从用户绑定的 GitLab 账户获取有效的 access token
     */
    private void injectGitLabToken(ServiceBuildRequest request) {
        String userId = request.getUserId();
        if (!StringUtils.hasText(userId)) {
            log.debug("No userId provided, skipping GitLab token injection");
            return;
        }
        
        // 获取用户的 GitLab token
        String gitLabToken = gitLabAuthService.getAccessToken(userId);
        if (!StringUtils.hasText(gitLabToken)) {
            log.debug("No GitLab token found for userId: {}", userId);
            return;
        }
        
        // 注入到 buildEnvVars
        Map<String, Object> envVars = request.getBuildEnvVars();
        if (envVars == null) {
            envVars = new HashMap<>();
            request.setBuildEnvVars(envVars);
        }
        
        // 覆盖前端传入的 GIT_TOKEN（前端传的可能是错误的登录 JWT）
        envVars.put("GIT_TOKEN", gitLabToken);
        log.info("Injected GitLab token for userId: {}", userId);
    }

    /**
     * 自动注入 MCP 配置到 buildEnvVars
     * 从岗位 configJson.mcp 中获取 MCP ID 列表，生成 SDK 兼容的 mcp.json JSON 字符串，
     * 写入 MCP_CONFIG 环境变量供 build.sh 使用。
     *
     * <p>如果岗位未配置 MCP（configJson.mcp 为空或不存在），正常跳过不报错。
     * 如果岗位配置了 MCP 但注入失败（ID 无效、Server 不存在等），抛出异常中断构建。</p>
     */
    private void injectMcpConfig(ServiceBuildRequest request) {
        Long roleId = request.getRoleId();
        if (roleId == null) {
            log.debug("No roleId provided, skipping MCP config injection");
            return;
        }

        RoleEntity role = roleService.getById(roleId);
        if (role == null) {
            log.debug("Role not found for roleId: {}, skipping MCP config injection", roleId);
            return;
        }

        // 从岗位 configJson.mcp 取 MCP 标识列表（兼容数字 ID 和名称字符串两种格式）
        List<String> mcpRefs = Collections.emptyList();
        if (role.getConfigJson() != null && role.getConfigJson().getMcp() != null) {
            mcpRefs = role.getConfigJson().getMcp().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toList());
        }

        // 岗位未配置 MCP → 正常跳过，不报错
        if (mcpRefs.isEmpty()) {
            log.debug("No MCP refs configured for role {}, skipping MCP config injection", roleId);
            return;
        }

        // --- 以下：岗位配置了 MCP，注入失败则中断构建 ---

        // 分离数字 ID 和名称字符串
        List<Long> mcpIds = new java.util.ArrayList<>();
        List<String> mcpNames = new java.util.ArrayList<>();
        for (String ref : mcpRefs) {
            try {
                mcpIds.add(Long.parseLong(ref));
            } catch (NumberFormatException e) {
                mcpNames.add(ref);
            }
        }

        // 按名称查询 MCP Server ID（兼容前端 mock 名称格式）
        if (!mcpNames.isEmpty()) {
            List<McpServerEntity> byNames = mcpServerService.list(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<McpServerEntity>()
                    .in(McpServerEntity::getName, mcpNames)
            );
            for (McpServerEntity entity : byNames) {
                mcpIds.add(entity.getId());
            }
            if (byNames.size() < mcpNames.size()) {
                List<String> foundNames = byNames.stream()
                        .map(McpServerEntity::getName)
                        .collect(Collectors.toList());
                List<String> missingNames = mcpNames.stream()
                        .filter(n -> !foundNames.contains(n))
                        .collect(Collectors.toList());
                throw new IllegalArgumentException(
                    String.format("岗位 [%d] 配置的 MCP Server 名称不存在: %s", roleId, missingNames));
            }
        }

        if (mcpIds.isEmpty()) {
            throw new IllegalArgumentException(
                String.format("岗位 [%d] 配置的 MCP 引用均无法解析为有效 ID: %s", roleId, mcpRefs));
        }

        // 生成 SDK 兼容的 mcp.json 配置
        try {
            Map<String, Object> mcpConfig = mcpServerService.generateMcpConfig(mcpIds);
            String mcpJsonString = objectMapper.writeValueAsString(mcpConfig);

            // 注入到 buildEnvVars
            Map<String, Object> envVars = request.getBuildEnvVars();
            if (envVars == null) {
                envVars = new HashMap<>();
                request.setBuildEnvVars(envVars);
            }
            envVars.put("MCP_CONFIG", mcpJsonString);
            log.info("Injected MCP config for roleId: {} ({} servers, resolved from {} refs)",
                     roleId, mcpIds.size(), mcpRefs.size());
        } catch (IllegalArgumentException e) {
            // 直接抛出，不包装
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                String.format("岗位 [%d] 的 MCP 配置生成失败: %s", roleId, e.getMessage()), e);
        }
    }

    /**
     * 自动注入 Skills 配置到 buildEnvVars
     * 从岗位 configJson.skills 中获取 Skill ID 列表，查询 DB 获取 branchName + latestCommit，
     * 生成 SKILLS_CONFIG JSON 字符串注入环境变量供 build.sh 使用。
     *
     * <p>如果岗位未配置 Skills（configJson.skills 为空或不存在），正常跳过不报错。
     * 如果岗位配置了 Skills 但注入失败（ID 无效、Skill 不存在等），抛出异常中断构建。</p>
     */
    private void injectSkillsConfig(ServiceBuildRequest request) {
        Long roleId = request.getRoleId();
        if (roleId == null) {
            log.debug("No roleId provided, skipping Skills config injection");
            return;
        }

        RoleEntity role = roleService.getById(roleId);
        if (role == null) {
            log.debug("Role not found for roleId: {}, skipping Skills config injection", roleId);
            return;
        }

        // 从岗位 configJson.skills 取 Skill 引用列表
        List<String> skillRefs = Collections.emptyList();
        if (role.getConfigJson() != null && role.getConfigJson().getSkills() != null) {
            skillRefs = role.getConfigJson().getSkills().stream()
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.toList());
        }

        // 岗位未配置 Skills → 正常跳过
        if (skillRefs.isEmpty()) {
            log.debug("No Skill refs configured for role {}, skipping Skills config injection", roleId);
            return;
        }

        // --- 以下：岗位配置了 Skills，注入失败则中断构建 ---

        // 分离数字 ID 和名称字符串
        List<Long> skillIds = new java.util.ArrayList<>();
        List<String> skillNames = new java.util.ArrayList<>();
        for (String ref : skillRefs) {
            try {
                skillIds.add(Long.parseLong(ref));
            } catch (NumberFormatException e) {
                skillNames.add(ref);
            }
        }

        // 按名称查询 Skill ID
        if (!skillNames.isEmpty()) {
            List<SkillEntity> byNames = skillService.list(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SkillEntity>()
                    .in(SkillEntity::getName, skillNames)
            );
            for (SkillEntity entity : byNames) {
                skillIds.add(entity.getId());
            }
            if (byNames.size() < skillNames.size()) {
                List<String> foundNames = byNames.stream()
                        .map(SkillEntity::getName)
                        .collect(Collectors.toList());
                List<String> missingNames = skillNames.stream()
                        .filter(n -> !foundNames.contains(n))
                        .collect(Collectors.toList());
                throw new IllegalArgumentException(
                    String.format("岗位 [%d] 配置的 Skill 名称不存在: %s", roleId, missingNames));
            }
        }

        if (skillIds.isEmpty()) {
            throw new IllegalArgumentException(
                String.format("岗位 [%d] 配置的 Skill 引用均无法解析为有效 ID: %s", roleId, skillRefs));
        }

        // 查询所有 SkillEntity
        List<SkillEntity> skills = skillService.listByIds(skillIds);
        if (skills.size() < skillIds.size()) {
            List<Long> foundIds = skills.stream().map(SkillEntity::getId).collect(Collectors.toList());
            List<Long> missingIds = skillIds.stream()
                    .filter(id -> !foundIds.contains(id))
                    .collect(Collectors.toList());
            throw new IllegalArgumentException(
                String.format("岗位 [%d] 配置的 Skill ID 不存在: %s", roleId, missingIds));
        }

        // 校验所有 Skill 都有 branchName
        for (SkillEntity skill : skills) {
            if (!StringUtils.hasText(skill.getBranchName())) {
                throw new IllegalArgumentException(
                    String.format("Skill [%s] 尚未关联 Git 分支，请先同步", skill.getName()));
            }
        }

        // 生成 SKILLS_CONFIG JSON
        try {
            List<Map<String, Object>> skillsList = new java.util.ArrayList<>();
            for (SkillEntity skill : skills) {
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("name", skill.getName());
                item.put("branch", skill.getBranchName());
                item.put("commit", skill.getLatestCommit());
                skillsList.add(item);
            }

            Map<String, Object> skillsConfig = new java.util.LinkedHashMap<>();
            skillsConfig.put("repoUrl", skillsRepoUrl);
            skillsConfig.put("token", skillsDeployToken);
            skillsConfig.put("skills", skillsList);

            String skillsJsonString = objectMapper.writeValueAsString(skillsConfig);

            // 注入到 buildEnvVars
            Map<String, Object> envVars = request.getBuildEnvVars();
            if (envVars == null) {
                envVars = new HashMap<>();
                request.setBuildEnvVars(envVars);
            }
            envVars.put("SKILLS_CONFIG", skillsJsonString);
            log.info("Injected Skills config for roleId: {} ({} skills, resolved from {} refs)",
                     roleId, skills.size(), skillRefs.size());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                String.format("岗位 [%d] 的 Skills 配置生成失败: %s", roleId, e.getMessage()), e);
        }
    }

    /**
     * 创建配置快照
     */
    private Map<String, Object> createConfigSnapshot(ServiceBuildRequest request, MergedConfig config) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("serviceId", request.getServiceId());
        snapshot.put("deployMode", request.getDeployMode() != null ? request.getDeployMode().name() : null);
        snapshot.put("runnerBaseImage", request.getRunnerBaseImage());
        snapshot.put("agentImage", config.getAgentImage());
        snapshot.put("podMode", config.getPodMode() != null ? config.getPodMode().name() : null);
        snapshot.put("runtimeMode", config.getPodMode() != null ? config.getPodMode().name() : null);
        snapshot.put("zzMode", config.getPodMode() != null && config.getPodMode().name().equals("SIDECAR") ? "ssh" : "local");
        snapshot.put("runnerImage", config.getRunnerImage());
        snapshot.put("podCount", config.getPodCount());
        snapshot.put("buildEnvVars", request.getBuildEnvVars());
        return snapshot;
    }
    
    /**
     * 异步构建服务（镜像构建 + K8s 资源创建）
     */
    @Async
    public void buildAsync(ServiceBuildRequest request, MergedConfig config) {
        String serviceId = request.getServiceId();
        String buildId = request.getBuildId();
        Long roleId = request.getRoleId();
        String roleName = request.getRoleName();
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("Async build started for service {}, buildId: {}", serviceId, buildId);
            
            // 更新构建记录状态为 BUILDING
            if (roleId != null && StringUtils.hasText(buildId)) {
                buildRecordService.markBuilding(buildId);
            }
            
            // 1. 镜像构建（如果启用，仅构建 Agent 镜像）
            if (imageBuildConfig.isEnabled() && request.getDeployMode() == DeployMode.K8S) {
                log.info("Image build enabled, starting image build for service {}", serviceId);
                
                // 发布 BUILD_STARTED 事件
                if (StringUtils.hasText(buildId)) {
                    eventPublisher.publishBuildStarted(buildId, buildId, roleId, roleName, 
                        config.getAgentImage());
                    eventPublisher.publishBuildProgress(buildId, buildId, "dockerfile", 
                        "Generating Dockerfile...");
                }
                
                ImageBuildResult buildResult = imageBuildService.buildImages(request);
                
                if (!buildResult.isSuccess()) {
                    log.error("Image build failed for service {}: {}", serviceId, buildResult.getErrorMessage());
                    
                    // 发布 BUILD_FAILED 事件并更新记录
                    long durationMs = System.currentTimeMillis() - startTime;
                    if (StringUtils.hasText(buildId)) {
                        eventPublisher.publishBuildFailed(buildId, buildId, "BUILD_ERROR", 
                            buildResult.getErrorMessage(), durationMs);
                        if (roleId != null) {
                            buildRecordService.markFailed(buildId, buildResult.getErrorMessage(), durationMs);
                        }
                    }
                    return;
                }
                
                // 只更新 Agent 镜像地址（Runner 镜像保持 runnerBaseImage 不变）
                config.setAgentImage(buildResult.getAgentImageTag());
                config.setImageBuildDurationMs(buildResult.getBuildDurationMs());
                
                // 发布镜像推送事件（如果已推送）
                if (buildResult.isPushed() && StringUtils.hasText(buildId)) {
                    eventPublisher.publishBuildPushed(buildId, buildId, buildResult.getAgentImageTag());
                }
                
                log.info("Image build completed for service {}, agentImage: {}, runnerImage: {}, duration: {}ms", 
                    serviceId, buildResult.getAgentImageTag(), config.getRunnerImage(), buildResult.getBuildDurationMs());
            } else {
                log.info("Image build disabled or not K8s mode, using base image for service {}", serviceId);
            }
            
            // 2. 创建 K8s 资源
            ServiceBuildResult result = orchestrator.buildService(config);
            
            long durationMs = System.currentTimeMillis() - startTime;
            
            if (result.isSuccess()) {
                log.info("Async build completed successfully for service {}", serviceId);
                
                // 保存 Snapshot 到内存 + Redis，确保后端重启后能恢复
                try {
                    snapshotService.saveSnapshot(request, result);
                } catch (Exception snapshotErr) {
                    log.warn("异步构建后保存 Snapshot 失败（不影响构建结果）: serviceId={}, error={}",
                            serviceId, snapshotErr.getMessage());
                }
                
                // 发布 BUILD_COMPLETED 事件并更新记录
                if (StringUtils.hasText(buildId)) {
                    eventPublisher.publishBuildCompleted(buildId, buildId, config.getAgentImage(), durationMs);
                    if (roleId != null) {
                        buildRecordService.markSuccess(buildId, config.getAgentImage(), durationMs);
                    }
                }
            } else {
                log.error("Async build failed for service {}: {}", serviceId, result.getErrorMessage());
                
                // 发布 BUILD_FAILED 事件并更新记录
                if (StringUtils.hasText(buildId)) {
                    eventPublisher.publishBuildFailed(buildId, buildId, "K8S_ERROR", 
                        result.getErrorMessage(), durationMs);
                    if (roleId != null) {
                        buildRecordService.markFailed(buildId, result.getErrorMessage(), durationMs);
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("Async build error for service {}: {}", serviceId, e.getMessage(), e);
            
            long durationMs = System.currentTimeMillis() - startTime;
            
            // 发布 BUILD_FAILED 事件并更新记录
            if (StringUtils.hasText(buildId)) {
                eventPublisher.publishBuildFailed(buildId, buildId, "INTERNAL_ERROR", 
                    e.getMessage(), durationMs);
                if (roleId != null) {
                    buildRecordService.markFailed(buildId, e.getMessage(), durationMs);
                }
            }
        }
    }
    
    /**
     * 预览生成的 Spec（不实际创建）
     */
    public GeneratedSpec preview(ServiceBuildRequest request) {
        MergedConfig config = configMergeService.merge(request);
        return orchestrator.previewSpec(config);
    }
    
    /**
     * 生成 Compose 构建包（tar.gz，包含完整的本地构建和部署材料）
     *
     * 不在服务端构建镜像，而是将构建所需的全部材料打包给用户，
     * 用户解压后执行 start.sh 即可一键完成镜像构建和容器启动。
     *
     * tar.gz 内容：
     *   docker-compose.yaml        - Compose 编排文件（build: . 方式）
     *   Dockerfile                 - 动态生成（基于 Compose 专用基础镜像）
     *   build.sh                   - Dockerfile 内部执行的镜像构建脚本
     *   config.json                - Agent 默认配置
     *   cedar-policies/            - Cedar 策略文件
     *   start.sh                   - 一键构建部署脚本
     *   README.md                  - 详细使用说明
     */
    public ServiceBuildResult generateComposePackage(ServiceBuildRequest request) {
        String serviceId = request.getServiceId();

        try {
            log.info("Generating Compose package for service {}", serviceId);

            // 1. 注入凭证和配置
            injectGitLabToken(request);
            injectMcpConfig(request);
            injectSkillsConfig(request);

            // 2. 配置融合
            MergedConfig config = configMergeService.merge(request);

            // 3. 生成各文件内容
            String composeYaml = composeGenerator.generateComposeYaml(config);
            String dockerfile = imageBuildService.generateDockerfile(
                    imageBuildConfig.getComposeBaseImage(),
                    request.getBuildEnvVars());
            String startScript = generateStartScript(serviceId);
            String readme = generateReadme(config);

            // 4. 打包 tar.gz
            byte[] tarBytes = buildComposeTar(serviceId, composeYaml, dockerfile, startScript, readme);

            log.info("Compose package generated for service {}, size: {} bytes", serviceId, tarBytes.length);
            return ServiceBuildResult.successCompose(serviceId, tarBytes);

        } catch (Exception e) {
            log.error("Compose package generation failed for service {}: {}", serviceId, e.getMessage(), e);
            return ServiceBuildResult.failed(serviceId, "INTERNAL_ERROR", e.getMessage());
        }
    }

    // ==================== Compose 打包内部方法 ====================

    private byte[] buildComposeTar(String serviceId, String composeYaml, String dockerfile,
                                   String startScript, String readme) throws java.io.IOException {
        String prefix = "ai-worker-" + serviceId + "/";
        var baos = new java.io.ByteArrayOutputStream();
        try (var gzos = new java.util.zip.GZIPOutputStream(baos);
             var tos = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzos)) {
            tos.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX);

            addTarEntry(tos, prefix + "docker-compose.yaml", composeYaml);
            addTarEntry(tos, prefix + "Dockerfile", dockerfile);
            addTarEntry(tos, prefix + "README.md", readme);
            addTarEntryExecutable(tos, prefix + "start.sh", startScript);

            addClasspathResourceToTar(tos, "scripts/build.sh", prefix + "build.sh");
            addClasspathResourceToTar(tos, "scripts/config.json", prefix + "config.json");
            addClasspathResourceToTar(tos, "scripts/00-platform.cedar", prefix + "cedar-policies/00-platform.cedar");

            tos.finish();
        }
        return baos.toByteArray();
    }

    private void addTarEntry(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tos,
                             String name, String content) throws java.io.IOException {
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry(name);
        entry.setSize(data.length);
        entry.setMode(0644);
        tos.putArchiveEntry(entry);
        tos.write(data);
        tos.closeArchiveEntry();
    }

    private void addTarEntryExecutable(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tos,
                                       String name, String content) throws java.io.IOException {
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry(name);
        entry.setSize(data.length);
        entry.setMode(0755);
        tos.putArchiveEntry(entry);
        tos.write(data);
        tos.closeArchiveEntry();
    }

    private void addClasspathResourceToTar(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tos,
                                           String classpathPath, String entryName) throws java.io.IOException {
        var resource = new org.springframework.core.io.ClassPathResource(classpathPath);
        if (resource.exists()) {
            try (var is = resource.getInputStream()) {
                byte[] data = is.readAllBytes();
                var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry(entryName);
                entry.setSize(data.length);
                entry.setMode(0644);
                tos.putArchiveEntry(entry);
                tos.write(data);
                tos.closeArchiveEntry();
            }
        } else {
            log.warn("Classpath resource not found: {}, skipping", classpathPath);
        }
    }

    /**
     * 生成一键构建部署脚本
     */
    private String generateStartScript(String serviceId) {
        return String.format("""
#!/bin/bash
# =============================================================================
# AI Worker 一键构建部署脚本
# Service ID: %1$s
#
# 用法:
#   ./start.sh          首次部署（构建镜像 + 启动容器）
#   ./start.sh rebuild  强制重新构建镜像
#   ./start.sh stop     停止服务
#   ./start.sh logs     查看实时日志
#   ./start.sh status   查看容器状态
#   ./start.sh clean    停止服务并删除镜像和数据卷
# =============================================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

SERVICE_ID="%1$s"
CONTAINER_NAME="ai-worker-${SERVICE_ID}"

log_info()  { echo -e "\\033[32m[INFO]\\033[0m  $(date '+%%H:%%M:%%S') $*"; }
log_warn()  { echo -e "\\033[33m[WARN]\\033[0m  $(date '+%%H:%%M:%%S') $*"; }
log_error() { echo -e "\\033[31m[ERROR]\\033[0m $(date '+%%H:%%M:%%S') $*" >&2; }

check_docker() {
    if ! command -v docker &>/dev/null; then
        log_error "Docker 未安装，请先安装 Docker"
        exit 1
    fi
    if ! docker compose version &>/dev/null; then
        log_error "Docker Compose V2 不可用，请升级 Docker"
        exit 1
    fi
}

do_build_and_start() {
    log_info "开始构建镜像并启动服务（Service ID: ${SERVICE_ID}）..."
    log_info "首次构建需要拉取基础镜像和安装依赖，预计耗时 5-10 分钟"
    echo ""
    docker compose up --build -d
    echo ""
    log_info "服务已启动"
    log_info "容器名称: ${CONTAINER_NAME}"
    log_info "查看日志: ./start.sh logs"
    log_info "停止服务: ./start.sh stop"
}

do_rebuild() {
    log_info "强制重新构建镜像..."
    docker compose build --no-cache
    docker compose up -d
    log_info "重新构建完成，服务已启动"
}

do_stop() {
    log_info "停止服务..."
    docker compose down
    log_info "服务已停止"
}

do_logs() {
    docker compose logs -f agent
}

do_status() {
    docker compose ps
}

do_clean() {
    log_warn "将停止服务、删除容器、镜像和数据卷"
    read -rp "确认? [y/N] " confirm
    if [[ "$confirm" =~ ^[Yy]$ ]]; then
        docker compose down -v --rmi local
        log_info "清理完成"
    else
        log_info "已取消"
    fi
}

check_docker

case "${1:-}" in
    rebuild)  do_rebuild ;;
    stop)     do_stop ;;
    logs)     do_logs ;;
    status)   do_status ;;
    clean)    do_clean ;;
    "")       do_build_and_start ;;
    *)
        echo "用法: $0 {rebuild|stop|logs|status|clean}"
        echo "  (无参数)  首次构建并启动"
        echo "  rebuild   强制重新构建镜像"
        echo "  stop      停止服务"
        echo "  logs      查看实时日志"
        echo "  status    查看容器状态"
        echo "  clean     停止并清理所有资源"
        exit 1
        ;;
esac
""", serviceId);
    }

    /**
     * 生成详细使用说明文档
     */
    private String generateReadme(MergedConfig config) {
        return String.format("""
# AI Worker 本地部署包

## 概述

本压缩包包含在本地服务器上以 Docker Compose 方式部署 AI Worker 的全部文件。
镜像在本地构建，无需从镜像仓库拉取构建好的镜像。

- **Service ID**: %s
- **User ID**: %s
- **基础镜像**: %s
- **运行模式**: Alone（单容器，ZZD_MODE=local）

## 文件清单

| 文件 | 说明 |
|------|------|
| `docker-compose.yaml` | Docker Compose 编排文件 |
| `Dockerfile` | 镜像构建定义（基于基础镜像，安装 SDK、zzd 等） |
| `build.sh` | Dockerfile 内部执行的构建脚本（安装依赖、配置权限等） |
| `config.json` | Agent 配置文件（claude_settings、workspace 等） |
| `cedar-policies/` | Cedar 安全策略文件 |
| `start.sh` | **一键构建部署脚本** |
| `README.md` | 本说明文档 |

## 环境要求

- Docker Engine 20.10+（需支持 Compose V2）
- 可访问 `docker.momo.com`（拉取基础镜像）
- 可访问 `git.wemomo.com`（构建时克隆 SDK 仓库）
- 建议内存 ≥ %s，CPU ≥ %s 核

## 快速开始

### 一键部署

```bash
# 解压
tar xzf ai-worker-%s.tar.gz
cd ai-worker-%s

# 首次部署（构建镜像 + 启动容器）
./start.sh
```

首次构建需要拉取基础镜像和安装依赖，预计耗时 **5-10 分钟**。
后续启动（镜像已构建过）只需几秒。

### 常用操作

```bash
# 查看实时日志
./start.sh logs

# 查看容器状态
./start.sh status

# 停止服务
./start.sh stop

# 强制重新构建（修改配置后）
./start.sh rebuild

# 清理所有资源（容器 + 镜像 + 数据卷）
./start.sh clean
```

### 手动操作（等效命令）

```bash
# 构建并启动
docker compose up --build -d

# 查看日志
docker compose logs -f agent

# 停止
docker compose down
```

## 运行时环境变量

以下环境变量已在 `docker-compose.yaml` 中配置：

| 变量 | 值 | 说明 |
|------|-----|------|
| `WORKSTATION_ID` | %s | 工位 ID |
| `SERVICE_ID` | %s | 服务 ID |
| `REDIS_URL` | %s | Redis 连接地址 |
| `API_BASE_URL` | %s | API 网关地址 |
| `WS_BASE_URL` | %s | WebSocket 网关地址 |
| `LLM_GATEWAY_URL` | %s | LLM 网关地址 |
| `CONFIG_FILE` | /opt/agent/config.json | Agent 配置文件路径 |
| `IDLE_TIMEOUT` | 86400 | 空闲超时（秒） |

## 资源配置

| 资源 | 请求 | 上限 |
|------|------|------|
| CPU | %s | %s |
| 内存 | %s | %s |

如需调整，修改 `docker-compose.yaml` 中 `deploy.resources` 部分。

## 数据持久化

工作目录挂载为 Docker named volume `workspace`，容器重启后数据保留。
执行 `./start.sh clean` 或 `docker compose down -v` 会删除数据卷。

## 故障排查

```bash
# 查看容器状态
docker compose ps

# 查看最近日志（不 follow）
docker compose logs --tail 100 agent

# 进入容器调试
docker compose exec agent bash

# 查看资源使用
docker stats ai-worker-%s
```
""",
            config.getServiceId(),
            config.getUserId(),
            imageBuildConfig.getComposeBaseImage(),
            config.getAgentResources().getMemoryLimit(),
            config.getAgentResources().getCpuLimit(),
            config.getServiceId(),
            config.getServiceId(),
            config.getWorkstationId() != null ? config.getWorkstationId() : config.getServiceId(),
            config.getServiceId(),
            config.getRedisUrl() != null ? config.getRedisUrl() : "",
            config.getApiBaseUrl() != null ? config.getApiBaseUrl() : "",
            config.getWsBaseUrl() != null ? config.getWsBaseUrl() : "",
            config.getLlmGatewayUrl() != null ? config.getLlmGatewayUrl() : "",
            config.getAgentResources().getCpuRequest(),
            config.getAgentResources().getCpuLimit(),
            config.getAgentResources().getMemoryRequest(),
            config.getAgentResources().getMemoryLimit(),
            config.getServiceId());
    }
    
    /**
     * 获取融合后的配置（用于调试）
     */
    public MergedConfig getMergedConfig(ServiceBuildRequest request) {
        return configMergeService.merge(request);
    }
}
