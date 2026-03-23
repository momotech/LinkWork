package com.linkwork.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.model.AuthConfig;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.PushResponseItem;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.linkwork.config.BuildQueueConfig;
import com.linkwork.config.ImageBuildConfig;
import com.linkwork.model.dto.ImageBuildResult;
import com.linkwork.model.dto.ServiceBuildRequest;
import com.linkwork.model.enums.DeployMode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.nio.file.DirectoryStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 镜像构建服务
 * 
 * 职责：
 * 1. 生成 Dockerfile（基础镜像 + ENV 声明 + 执行 build.sh）
 * 2. 调用 Docker API 构建 Agent 镜像
 * 3. 推送镜像到仓库（K8s 模式）
 * 
 * 设计说明：
 * - 仅构建 Agent 镜像，Runner 由运行时 agent 启动
 * - token 放入 buildEnvVars，在 build.sh 执行前 export
 */
@Service
@Slf4j
public class ImageBuildService {
    
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final Pattern LOCAL_BUILD_REPO_PATTERN = Pattern.compile("^service-.*-agent$");
    
    private final ImageBuildConfig config;
    private final BuildQueueConfig buildQueueConfig;
    private final ObjectMapper objectMapper;
    private final ScheduleEventPublisher eventPublisher;
    private final BuildLogBuffer logBuffer;
    private final NfsStorageService nfsStorageService;
    private final BuildRecordService buildRecordService;
    private DockerClient dockerClient;
    
    public ImageBuildService(ImageBuildConfig config, 
                             BuildQueueConfig buildQueueConfig,
                             ObjectMapper objectMapper,
                             ScheduleEventPublisher eventPublisher,
                             BuildLogBuffer logBuffer,
                             NfsStorageService nfsStorageService,
                             BuildRecordService buildRecordService) {
        this.config = config;
        this.buildQueueConfig = buildQueueConfig;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.logBuffer = logBuffer;
        this.nfsStorageService = nfsStorageService;
        this.buildRecordService = buildRecordService;
    }
    
    @PostConstruct
    public void init() {
        log.info("Initializing Docker client with host: {}", config.getDockerHost());
        
        DefaultDockerClientConfig.Builder configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerHost(config.getDockerHost());

        // 注册 Registry 凭证到 DockerClientConfig，确保 Docker Daemon 能正确完成 Harbor Token 鉴权
        String registry = config.getRegistry();
        String registryHost = "";
        if (StringUtils.hasText(registry) && StringUtils.hasText(config.getRegistryUsername())) {
            registryHost = registry;
            if (registryHost.contains("/")) {
                registryHost = registryHost.substring(0, registryHost.indexOf("/"));
            }
            configBuilder
                .withRegistryUrl("http://" + registryHost)
                .withRegistryUsername(config.getRegistryUsername())
                .withRegistryPassword(config.getRegistryPassword());
            log.info("Registry credentials configured for: {}", registryHost);
        }

        // ★ 写入 ~/.docker/config.json，确保 Docker Daemon 能从凭据文件读取认证信息
        if (StringUtils.hasText(registryHost) && StringUtils.hasText(config.getRegistryUsername())) {
            writeDockerConfigJson(registryHost, config.getRegistryUsername(), config.getRegistryPassword());
        }

        DockerClientConfig clientConfig = configBuilder.build();
        
        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
            .dockerHost(clientConfig.getDockerHost())
            .sslConfig(clientConfig.getSSLConfig())
            .maxConnections(100)
            .connectionTimeout(Duration.ofSeconds(30))
            .responseTimeout(Duration.ofSeconds(config.getBuildTimeout()))
            .build();
        
        this.dockerClient = DockerClientImpl.getInstance(clientConfig, httpClient);
        
        // 确保构建上下文根目录存在
        try {
            Path buildContextBase = Path.of(config.getBuildContextDir());
            if (!Files.exists(buildContextBase)) {
                Files.createDirectories(buildContextBase);
                log.info("Created build context directory: {}", buildContextBase);
            }
        } catch (IOException e) {
            log.warn("Failed to create build context directory: {}", e.getMessage());
        }
        
        log.info("Docker client initialized successfully");
    }
    
    @PreDestroy
    public void cleanup() {
        if (dockerClient != null) {
            try {
                dockerClient.close();
            } catch (IOException e) {
                log.warn("Failed to close Docker client: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 写入 Docker 配置文件 ~/.docker/config.json
     * 确保 Docker daemon 能通过凭据文件完成 Harbor Token 鉴权
     */
    private void writeDockerConfigJson(String registryHost, String username, String password) {
        try {
            Path dockerConfigDir = Path.of(System.getProperty("user.home"), ".docker");
            Files.createDirectories(dockerConfigDir);
            Path configFile = dockerConfigDir.resolve("config.json");

            String auth = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );

            // 同时写入 带http://前缀 和 不带前缀 的两种格式，确保匹配
            String configJson = "{\n" +
                "  \"auths\": {\n" +
                "    \"" + registryHost + "\": {\n" +
                "      \"auth\": \"" + auth + "\"\n" +
                "    },\n" +
                "    \"http://" + registryHost + "\": {\n" +
                "      \"auth\": \"" + auth + "\"\n" +
                "    }\n" +
                "  }\n" +
                "}";

            Files.writeString(configFile, configJson);
            log.info("Docker config.json written to: {}", configFile);
        } catch (Exception e) {
            log.warn("Failed to write Docker config.json: {}", e.getMessage());
        }
    }

    /**
     * 构建 Agent 镜像
     * 
     * @param request 服务构建请求
     * @return 构建结果
     */
    public ImageBuildResult buildImages(ServiceBuildRequest request) {
        String serviceId = request.getServiceId();
        String buildId = request.getBuildId();
        long startTime = System.currentTimeMillis();
        
        log.info("Starting image build for service: {}, buildId: {}", serviceId, buildId);
        publishLog(buildId, "info", "=== 开始构建镜像 ===");
        publishLog(buildId, "info", "服务ID: " + serviceId);
        
        try {
            // 生成时间戳 tag
            String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
            
            // 解析镜像仓库
            String registry = resolveRegistry(request);
            boolean shouldPush = shouldPushImage(request, registry);
            publishLog(buildId, "info", "镜像仓库: " + (StringUtils.hasText(registry) ? registry : "本地"));
            
            // 构建 Agent 镜像
            String agentBaseImage = resolveAgentBaseImage(request);
            publishLog(buildId, "info", "基础镜像: " + agentBaseImage);
            publishLog(buildId, "info", "");
            publishLog(buildId, "info", "=== 开始 Docker 构建 ===");
            
            String agentImageTag = buildAgentImage(serviceId, timestamp, registry, 
                agentBaseImage, request.getBuildEnvVars(), buildId);
            
            publishLog(buildId, "info", "");
            publishLog(buildId, "info", "镜像构建成功: " + agentImageTag);
            
            // K8s 模式推送镜像（仅请求配置了镜像仓库时推送）
            boolean pushed = false;
            if (shouldPush) {
                publishLog(buildId, "info", "");
                publishLog(buildId, "info", "=== 开始推送镜像 ===");
                pushImage(agentImageTag, buildId, registry);
                pushed = true;
                publishLog(buildId, "info", "镜像推送成功");
                
                // 推送成功后删除本地镜像（K8s 会从仓库拉取，不需要保留本地副本）
                removeLocalImage(agentImageTag, buildId);
            } else {
                publishLog(buildId, "warn", "跳过镜像推送（未配置镜像仓库或非 K8S 模式）");
                log.info("Image push skipped (deployMode={}, registry={}) for image: {}",
                    request.getDeployMode(), registry, agentImageTag);
                syncLocalImageToKindIfNeeded(request, agentImageTag, buildId);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            publishLog(buildId, "info", "");
            publishLog(buildId, "info", "=== 构建完成 ===");
            publishLog(buildId, "info", String.format("总耗时: %.1f 秒", duration / 1000.0));
            markBuildCompleted(buildId, true);
            log.info("Image build completed for service: {}, duration: {}ms", serviceId, duration);
            
            return ImageBuildResult.success(agentImageTag, null, duration, pushed);
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            publishLog(buildId, "error", "");
            publishLog(buildId, "error", "=== 构建失败 ===");
            publishLog(buildId, "error", "错误: " + e.getMessage());
            markBuildCompleted(buildId, false);
            log.error("Image build failed for service: {}, duration: {}ms, error: {}", 
                serviceId, duration, e.getMessage(), e);
            return ImageBuildResult.failed(e.getMessage());
        }
    }
    
    /**
     * 发布构建日志
     * 优先使用 BuildLogBuffer（直接 SSE 推送），同时保留 Redis Stream 兼容
     */
    private void publishLog(String buildId, String level, String message) {
        if (!StringUtils.hasText(buildId)) return;
        
        // 写入日志缓冲区（SSE 直接推送）
        if (logBuffer != null) {
            logBuffer.addLog(buildId, level, message);
        }
    }
    
    /**
     * 标记构建完成并上传日志到 OSS
     * @param buildId 构建 ID
     * @param success 是否成功
     */
    private void markBuildCompleted(String buildId, boolean success) {
        if (logBuffer != null && StringUtils.hasText(buildId)) {
            logBuffer.markCompleted(buildId, success);
            
            // 上传日志到 OSS
            uploadBuildLogToOss(buildId);
            
            // 10 分钟后清理日志缓冲区
            logBuffer.scheduleCleanup(buildId, 10);
        }
    }
    
    /**
     * 上传构建日志到 NFS 存储
     */
    private void uploadBuildLogToOss(String buildId) {
        try {
            if (nfsStorageService == null || !nfsStorageService.isConfigured()) {
                log.warn("NFS 存储未配置，跳过日志上传: {}", buildId);
                return;
            }
            
            String logContent = logBuffer.exportAsText(buildId);
            if (logContent == null || logContent.isEmpty()) {
                log.warn("构建日志为空，跳过上传: {}", buildId);
                return;
            }
            
            String filename = buildId + ".txt";
            
            String logPath = nfsStorageService.uploadText(logContent, "build-logs", filename);
            log.info("构建日志已上传到 NFS: {} -> {}", buildId, logPath);
            
            if (buildRecordService != null) {
                buildRecordService.updateLogUrl(buildId, logPath);
            }
            
        } catch (Exception e) {
            log.error("上传构建日志失败: {}, error: {}", buildId, e.getMessage(), e);
        }
    }
    
    /**
     * 解析镜像仓库地址
     */
    private String resolveRegistry(ServiceBuildRequest request) {
        if (!StringUtils.hasText(request.getImageRegistry())) {
            return "";
        }
        String registry = request.getImageRegistry().trim();
        while (registry.endsWith("/")) {
            registry = registry.substring(0, registry.length() - 1);
        }
        return registry;
    }

    /**
     * 仅当 K8s 且请求显式配置了仓库时才推送
     */
    private boolean shouldPushImage(ServiceBuildRequest request, String registry) {
        return request.getDeployMode() == DeployMode.K8S && StringUtils.hasText(registry);
    }

    private void syncLocalImageToKindIfNeeded(ServiceBuildRequest request, String imageTag, String buildId) {
        if (request.getDeployMode() != DeployMode.K8S) {
            return;
        }
        if (!config.isAutoLoadToKind()) {
            log.info("Auto kind image load disabled, skip local image sync: {}", imageTag);
            return;
        }
        if (hasRegistryHost(imageTag)) {
            return;
        }

        List<Container> kindNodes = findKindNodeContainers();
        if (kindNodes.isEmpty()) {
            throw new IllegalStateException("本地镜像模式下未发现 Kind 节点，无法自动分发镜像。"
                + "请配置 imageRegistry 推送远程仓库，或检查 Kind 集群/节点标签配置。");
        }

        publishLog(buildId, "info", "检测到本地镜像，开始同步到 Kind 节点");
        for (Container node : kindNodes) {
            String nodeName = resolveContainerName(node);
            if (!StringUtils.hasText(nodeName)) {
                continue;
            }
            publishLog(buildId, "debug", "同步镜像到节点: " + nodeName);
            importImageIntoKindNode(imageTag, node, nodeName);
        }
        publishLog(buildId, "info", "Kind 节点镜像同步完成");
    }

    private List<Container> findKindNodeContainers() {
        List<Container> all = dockerClient.listContainersCmd().withShowAll(false).exec();
        List<Container> nodes = new ArrayList<>();
        String expectedCluster = normalize(config.getKindClusterName());

        for (Container c : all) {
            Map<String, String> labels = c.getLabels();
            if (labels == null) {
                continue;
            }
            String cluster = normalize(labels.get("io.x-k8s.kind.cluster"));
            String role = normalize(labels.get("io.x-k8s.kind.role"));
            if (!StringUtils.hasText(cluster) || !StringUtils.hasText(role)) {
                continue;
            }
            if (StringUtils.hasText(expectedCluster) && !expectedCluster.equals(cluster)) {
                continue;
            }
            if (!"control-plane".equals(role) && !"worker".equals(role)) {
                continue;
            }
            nodes.add(c);
        }
        return nodes;
    }

    private void importImageIntoKindNode(String imageTag, Container node, String nodeName) {
        ExecCreateCmdResponse exec = dockerClient.execCreateCmd(node.getId())
            .withAttachStdin(true)
            .withAttachStdout(true)
            .withAttachStderr(true)
            .withCmd("ctr", "-n", "k8s.io", "images", "import", "-")
            .exec();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream tarStream = dockerClient.saveImageCmd(imageTag).exec()) {
            ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame item) {
                    try {
                        if (item != null && item.getPayload() != null) {
                            output.write(item.getPayload());
                        }
                    } catch (IOException ignored) {
                        // 输出只用于排障，写失败不影响主流程
                    }
                    super.onNext(item);
                }
            };

            dockerClient.execStartCmd(exec.getId())
                .withStdIn(tarStream)
                .exec(callback)
                .awaitCompletion(config.getKindLoadTimeout(), TimeUnit.SECONDS);

            InspectExecResponse inspect = dockerClient.inspectExecCmd(exec.getId()).exec();
            Long exitCode = inspect != null ? inspect.getExitCodeLong() : null;
            if (exitCode == null || exitCode != 0L) {
                String details = output.toString();
                throw new IllegalStateException("Kind 节点导入失败: node=" + nodeName
                    + ", exitCode=" + exitCode + ", output=" + details);
            }
            log.info("Image imported to kind node successfully: node={}, image={}", nodeName, imageTag);
        } catch (Exception e) {
            throw new RuntimeException("同步镜像到 Kind 节点失败: node=" + nodeName + ", image=" + imageTag, e);
        }
    }

    private boolean hasRegistryHost(String image) {
        if (!StringUtils.hasText(image)) {
            return false;
        }
        String value = image.trim();
        int slash = value.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String first = value.substring(0, slash);
        return first.contains(".") || first.contains(":") || "localhost".equals(first);
    }

    private String resolveContainerName(Container container) {
        if (container == null || container.getNames() == null || container.getNames().length == 0) {
            return "";
        }
        String name = container.getNames()[0];
        if (name == null) {
            return "";
        }
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
    
    /**
     * 解析 Agent 基础镜像（使用系统默认配置）
     */
    private String resolveAgentBaseImage(ServiceBuildRequest request) {
        // 直接使用系统默认配置，不再从请求中获取
        return config.getDefaultAgentBaseImage();
    }
    
    /**
     * 构建 Agent 镜像
     */
    private String buildAgentImage(String serviceId, String timestamp, String registry,
                                   String baseImage, Map<String, Object> envVars, 
                                   String buildId) throws Exception {
        String imageName = generateImageName(registry, serviceId, "agent", timestamp);
        
        log.info("Building Agent image: {}, baseImage: {}", imageName, baseImage);
        publishLog(buildId, "info", "目标镜像: " + imageName);
        
        // 确保基础镜像已缓存到本地（避免每次 docker build 都从 registry 拉取）
        ensureBaseImageCached(baseImage, buildId);

        // 创建构建上下文目录
        publishLog(buildId, "debug", "创建构建上下文...");
        Path buildContextPath = createBuildContext(serviceId, baseImage, envVars);
        publishLog(buildId, "debug", "构建上下文路径: " + buildContextPath);
        
        try {
            // 执行构建
            buildImage(buildContextPath, imageName, buildId);
            return imageName;
        } finally {
            // 清理构建上下文
            publishLog(buildId, "debug", "清理构建上下文...");
            cleanupBuildContext(buildContextPath);
            // 清理残留的历史构建上下文
            cleanupStaleContexts();
        }
    }
    
    /**
     * 确保基础镜像已缓存到本地
     *
     * 先通过 inspectImageCmd 检查本地是否已存在该镜像：
     * - 已存在 → 直接使用，跳过网络拉取（毫秒级）
     * - 不存在 → 执行 pull 并输出进度日志
     *
     * 这样 docker build 的 FROM 指令不再需要联网验证/拉取，大幅加快构建速度。
     */
    private void ensureBaseImageCached(String baseImage, String buildId) {
        try {
            dockerClient.inspectImageCmd(baseImage).exec();
            publishLog(buildId, "info", "基础镜像已缓存在本地，跳过拉取");
            log.info("Base image already cached locally: {}", baseImage);
        } catch (NotFoundException e) {
            // 本地不存在，需要拉取
            publishLog(buildId, "info", "本地未找到基础镜像，开始拉取: " + baseImage);
            log.info("Base image not found locally, pulling: {}", baseImage);
            try {
                final String finalBuildId = buildId;
                dockerClient.pullImageCmd(baseImage)
                    .exec(new ResultCallback.Adapter<PullResponseItem>() {
                        @Override
                        public void onNext(PullResponseItem item) {
                            if (item.getStatus() != null) {
                                String status = item.getStatus();
                                // 只记录关键进度，避免日志刷屏
                                if (status.contains("Pulling") || status.contains("Pull complete") ||
                                    status.contains("Downloaded") || status.contains("digest") ||
                                    status.contains("Status")) {
                                    publishLog(finalBuildId, "info", "[PULL] " + status);
                                }
                            }
                        }
                    })
                    .awaitCompletion(config.getBuildTimeout(), TimeUnit.SECONDS);
                publishLog(buildId, "info", "基础镜像拉取完成");
                log.info("Base image pulled successfully: {}", baseImage);
            } catch (Exception pullEx) {
                publishLog(buildId, "warn", "基础镜像拉取失败（将由 docker build 重试）: " + pullEx.getMessage());
                log.warn("Failed to pre-pull base image: {}, will retry during build", baseImage, pullEx);
            }
        } catch (Exception e) {
            publishLog(buildId, "warn", "检查本地镜像异常: " + e.getMessage());
            log.warn("Failed to inspect local image: {}", baseImage, e);
        }
    }

    /**
     * 生成镜像名称
     * 格式：{registry}/service-{serviceId}-agent:{serviceId}-{timestamp}
     */
    private String generateImageName(String registry, String serviceId, String type, String timestamp) {
        String tag = serviceId + "-" + timestamp;
        String imageName = String.format("service-%s-%s:%s", serviceId, type, tag);
        if (StringUtils.hasText(registry)) {
            return registry + "/" + imageName;
        }
        return imageName;
    }
    
    /**
     * 创建构建上下文目录
     */
    private Path createBuildContext(String serviceId, String baseImage, 
                                    Map<String, Object> envVars) throws IOException {
        // 创建临时目录
        Path contextDir = Files.createTempDirectory(
            Path.of(config.getBuildContextDir()), 
            String.format("build-%s-", serviceId)
        );

        // 生成 Dockerfile
        String dockerfile = generateDockerfile(baseImage, envVars);
        Files.writeString(contextDir.resolve("Dockerfile"), dockerfile);
        
        // 复制 build.sh（优先从 classpath 读取，其次从文件系统）
        boolean buildScriptCopied = false;
        
        // 1. 尝试从 classpath 读取（打包后的资源）
        try {
            ClassPathResource resource = new ClassPathResource("scripts/build.sh");
            if (resource.exists()) {
                try (var inputStream = resource.getInputStream()) {
                    Files.copy(inputStream, contextDir.resolve("build.sh"));
                    buildScriptCopied = true;
                    log.debug("Build script loaded from classpath");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load build script from classpath: {}", e.getMessage());
        }
        
        // 2. 如果 classpath 中没有，尝试从文件系统读取
        if (!buildScriptCopied) {
            Path buildScript = Path.of(config.getBuildScriptPath());
            if (Files.exists(buildScript)) {
                Files.copy(buildScript, contextDir.resolve("build.sh"));
                buildScriptCopied = true;
                log.debug("Build script loaded from file system: {}", buildScript);
            }
        }
        
        // 3. 如果都没有，创建空的 build.sh 占位
        if (!buildScriptCopied) {
            Files.writeString(contextDir.resolve("build.sh"), "#!/bin/bash\n# Placeholder build script\necho 'Build script executed'\n");
            log.warn("Build script not found, using placeholder");
        }
        
        // 复制 Cedar 策略文件到构建上下文（zzd fail-closed 需要至少一个 .cedar 文件）
        Path cedarDir = contextDir.resolve("cedar-policies");
        Files.createDirectories(cedarDir);
        try {
            ClassPathResource cedarResource = new ClassPathResource("scripts/00-platform.cedar");
            if (cedarResource.exists()) {
                try (var inputStream = cedarResource.getInputStream()) {
                    Files.copy(inputStream, cedarDir.resolve("00-platform.cedar"));
                    log.debug("Cedar policy loaded from classpath");
                }
            } else {
                log.warn("00-platform.cedar not found in classpath");
            }
        } catch (Exception e) {
            log.warn("Failed to load cedar policy: {}", e.getMessage());
        }

        // 复制默认 config.json 到构建上下文（镜像内兜底，运行时由 ConfigMap 覆盖挂载）
        try {
            ClassPathResource configResource = new ClassPathResource("scripts/config.json");
            if (configResource.exists()) {
                try (var inputStream = configResource.getInputStream()) {
                    Files.copy(inputStream, contextDir.resolve("config.json"));
                    log.debug("Default config.json loaded from classpath");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load default config.json: {}", e.getMessage());
        }

        int copied = copyBundledBuildAssets(contextDir);
        if (copied <= 0) {
            throw new IOException("Bundled build-assets are required but missing or empty");
        }
        log.debug("Bundled build assets copied into context: {} files", copied);

        log.debug("Build context created at: {}", contextDir);
        return contextDir;
    }

    private boolean hasBundledBuildAssets() {
        return new ClassPathResource("build-assets/manifest.txt").exists();
    }

    private int copyBundledBuildAssets(Path contextDir) throws IOException {
        ClassPathResource manifestResource = new ClassPathResource("build-assets/manifest.txt");
        if (!manifestResource.exists()) {
            return 0;
        }

        Path assetsRoot = contextDir.resolve("build-assets").toAbsolutePath().normalize();
        int copied = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(manifestResource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String relative = line.trim();
                if (relative.isEmpty() || relative.startsWith("#")) {
                    continue;
                }
                ClassPathResource resource = new ClassPathResource("build-assets/" + relative);
                if (!resource.exists()) {
                    log.warn("Bundled build asset listed but missing: {}", relative);
                    continue;
                }

                Path target = assetsRoot.resolve(relative).normalize();
                if (!target.startsWith(assetsRoot)) {
                    throw new IOException("Illegal bundled build asset path: " + relative);
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                try (InputStream inputStream = resource.getInputStream()) {
                    Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
                }
                copied++;
            }
        }
        return copied;
    }
    
    /**
     * 生成 Dockerfile 内容
     * Package-private：供 Compose 模式复用
     */
    String generateDockerfile(String baseImage, Map<String, Object> envVars) {
        StringBuilder sb = new StringBuilder();

        String sdkSourcePath = StringUtils.hasText(config.getSdkSourcePath())
            ? config.getSdkSourcePath().trim()
            : "/opt/linkwork-agent-build/sdk-source";
        String zzdBinariesPath = StringUtils.hasText(config.getZzdBinariesPath())
            ? config.getZzdBinariesPath().trim()
            : "/opt/linkwork-agent-build/zzd-binaries";
        String buildAssetsRoot = sdkSourcePath.contains("/")
            ? sdkSourcePath.substring(0, sdkSourcePath.lastIndexOf('/'))
            : "/opt/linkwork-agent-build";
        String startScriptsPath = buildAssetsRoot + "/start-scripts";
        
        // FROM 指令
        sb.append("# Auto-generated Dockerfile\n");
        sb.append("FROM ").append(baseImage).append("\n\n");
        
        // ENV 指令（注入环境变量）
        if (envVars != null && !envVars.isEmpty()) {
            sb.append("# Environment variables from build request\n");
            for (Map.Entry<String, Object> entry : envVars.entrySet()) {
                String key = entry.getKey();
                Object valueObj = entry.getValue();
                String valueStr;
                
                if (valueObj instanceof String) {
                    valueStr = (String) valueObj;
                } else {
                    try {
                        // 对象/数组序列化为 JSON 字符串
                        valueStr = objectMapper.writeValueAsString(valueObj);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to serialize env var {} to JSON", key, e);
                        valueStr = String.valueOf(valueObj);
                    }
                }
                
                // 转义特殊字符
                String value = escapeEnvValue(valueStr);
                sb.append("ENV ").append(key).append("=").append(value).append("\n");
            }
            sb.append("\n");
        }
        
        // Bundled assets from project resources
        sb.append("# Bundled build assets from project resources\n");
        sb.append("RUN mkdir -p ").append(zzdBinariesPath).append(" \\\n");
        sb.append("    && mkdir -p ").append(sdkSourcePath).append(" \\\n");
        sb.append("    && mkdir -p ").append(startScriptsPath).append("\n");
        sb.append("COPY build-assets/ ").append(buildAssetsRoot).append("/\n\n");

        // Cedar 策略文件 → /tmp/cedar-policies/ (build.sh download_cedar_policies 会从这里读取)
        sb.append("# Cedar policy files for build.sh to deploy\n");
        sb.append("COPY cedar-policies/ /tmp/cedar-policies/\n\n");
        
        // 默认 config.json → /opt/agent/config.json（build.sh finalize_permissions 会设置权限）
        sb.append("# Default agent config (overridden at runtime by ConfigMap mount)\n");
        sb.append("RUN mkdir -p /opt/agent\n");
        sb.append("COPY config.json /opt/agent/config.json\n\n");

        // 复制并执行 build.sh（生产版 v2 — 完整部署: 依赖检查、zzd、SDK、agent用户、权限）
        sb.append("# Copy and execute build script (production v2)\n");
        sb.append("COPY build.sh /build.sh\n");
        sb.append("RUN chmod +x /build.sh && /build.sh\n\n");
        
        // ENTRYPOINT
        sb.append("# Set entrypoint\n");
        sb.append("ENTRYPOINT [\"").append(config.getEntrypointScript()).append("\"]\n");
        
        return sb.toString();
    }
    
    /**
     * 转义环境变量值中的特殊字符
     */
    String escapeEnvValue(String value) {
        if (value == null) {
            return "\"\"";
        }
        // 如果包含空格或特殊字符，使用双引号包裹
        if (value.contains(" ") || value.contains("\"") || value.contains("$") || 
            value.contains("\\") || value.contains("\n")) {
            return "\"" + value.replace("\\", "\\\\")
                              .replace("\"", "\\\"")
                              .replace("$", "\\$")
                              .replace("\n", "\\n") + "\"";
        }
        return value;
    }
    
    /**
     * 执行 Docker build
     */
    private void buildImage(Path contextPath, String imageTag, String buildId) throws Exception {
        log.info("Executing docker build: tag={}, context={}", imageTag, contextPath);
        publishLog(buildId, "info", "");
        publishLog(buildId, "info", "--- Docker Build 日志 ---");
        
        File contextDir = contextPath.toFile();
        File dockerfile = contextPath.resolve("Dockerfile").toFile();
        
        // 用于在回调中引用 buildId（必须是 effectively final）
        final String finalBuildId = buildId;
        
        BuildImageResultCallback callback = new BuildImageResultCallback() {
            @Override
            public void onNext(BuildResponseItem item) {
                // 发布 Docker 构建的实时日志
                if (item.getStream() != null) {
                    String logLine = item.getStream().trim();
                    if (!logLine.isEmpty()) {
                        log.debug("Build output: {}", logLine);
                        // 将 Docker 日志发布到 Redis Stream
                        publishLog(finalBuildId, "info", logLine);
                    }
                }
                if (item.getErrorDetail() != null) {
                    String errorMsg = item.getErrorDetail().getMessage();
                    log.error("Build error: {}", errorMsg);
                    publishLog(finalBuildId, "error", "[ERROR] " + errorMsg);
                }
                // 发布构建进度信息
                if (item.getStatus() != null) {
                    String status = item.getStatus();
                    log.debug("Build status: {}", status);
                    publishLog(finalBuildId, "debug", "[STATUS] " + status);
                }
                super.onNext(item);
            }
        };
        
        String imageId = dockerClient.buildImageCmd()
            .withDockerfile(dockerfile)
            .withBaseDirectory(contextDir)
            .withTags(Collections.singleton(imageTag))
            .withNoCache(false)
            .withPull(false)  // 使用本地镜像，避免网络问题
            .exec(callback)
            .awaitImageId(config.getBuildTimeout(), TimeUnit.SECONDS);
        
        publishLog(buildId, "info", "");
        publishLog(buildId, "info", "--- Docker Build 完成 ---");
        publishLog(buildId, "info", "镜像ID: " + imageId);
        log.info("Image built successfully: tag={}, imageId={}", imageTag, imageId);
    }
    
    /**
     * 推送镜像到仓库（带重试机制）
     */
    private void pushImage(String imageTag, String buildId, String registry) throws Exception {
        log.info("Pushing image: {}", imageTag);
        publishLog(buildId, "info", "正在推送镜像: " + imageTag);
        
        final int maxRetries = 3;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                doPushImage(imageTag, buildId, registry, attempt);
                // 推送成功
                publishLog(buildId, "info", "镜像推送完成: " + imageTag);
                log.info("Image pushed successfully: {}", imageTag);
                return;
            } catch (Exception e) {
                lastException = e;
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                boolean isAuthError = errorMsg.contains("401") || errorMsg.contains("Unauthorized")
                    || errorMsg.contains("authentication") || errorMsg.contains("unauthorized");

                if (isAuthError && attempt < maxRetries) {
                    publishLog(buildId, "warn", String.format(
                        "推送失败 (第%d次, 认证错误), %d秒后重试...", attempt, attempt * 5));
                    log.warn("Push attempt {} failed with auth error, retrying in {}s: {}",
                        attempt, attempt * 5, errorMsg);

                    // 重新写入凭据文件并刷新认证
                    String registryHost = extractRegistryHost(registry);
                    if (StringUtils.hasText(registryHost) && StringUtils.hasText(config.getRegistryUsername())) {
                        writeDockerConfigJson(registryHost, config.getRegistryUsername(), config.getRegistryPassword());
                    }

                    Thread.sleep(attempt * 5000L);
                } else if (!isAuthError && attempt < maxRetries) {
                    publishLog(buildId, "warn", String.format(
                        "推送失败 (第%d次), %d秒后重试...", attempt, attempt * 3));
                    Thread.sleep(attempt * 3000L);
                } else {
                    // 最后一次重试也失败了
                    publishLog(buildId, "error", String.format(
                        "推送失败 (第%d次/%d次): %s", attempt, maxRetries, errorMsg));
                }
            }
        }

        throw new RuntimeException("镜像推送失败 (重试" + maxRetries + "次后): " +
            (lastException != null ? lastException.getMessage() : "unknown error"));
    }

    /**
     * 执行单次镜像推送
     */
    private void doPushImage(String imageTag, String buildId, String registry, int attempt) throws Exception {
        // 构建认证配置
        AuthConfig authConfig = null;
        if (StringUtils.hasText(config.getRegistryUsername()) && 
            StringUtils.hasText(config.getRegistryPassword())) {
            String registryAddress = extractRegistryHost(registry);
            // 使用 http:// 前缀明确标识为 HTTP 仓库，避免 daemon 默认走 HTTPS token 交换
            authConfig = new AuthConfig()
                .withRegistryAddress("http://" + registryAddress)
                .withUsername(config.getRegistryUsername())
                .withPassword(config.getRegistryPassword());
            publishLog(buildId, "debug", String.format(
                "使用认证推送 (registry: %s, attempt: %d)...", registryAddress, attempt));
        }
        
        final String finalBuildId = buildId;
        // 用于捕获回调中的推送错误
        final StringBuilder pushError = new StringBuilder();
        
        ResultCallback.Adapter<PushResponseItem> callback = new ResultCallback.Adapter<PushResponseItem>() {
            @Override
            public void onNext(PushResponseItem item) {
                if (item.getStatus() != null) {
                    String status = item.getStatus();
                    log.debug("Push status: {}", status);
                    // 过滤一些噪音日志，只发布关键状态
                    if (status.contains("Pushing") || status.contains("Pushed") || 
                        status.contains("Layer") || status.contains("digest")) {
                        publishLog(finalBuildId, "info", status);
                    }
                }
                if (item.getProgressDetail() != null && item.getProgressDetail().getCurrent() != null) {
                    // 推送进度，可选择性发布
                    Long current = item.getProgressDetail().getCurrent();
                    Long total = item.getProgressDetail().getTotal();
                    if (total != null && total > 0) {
                        int percent = (int) (current * 100 / total);
                        if (percent % 20 == 0) {  // 每 20% 发布一次
                            publishLog(finalBuildId, "debug", String.format("推送进度: %d%%", percent));
                        }
                    }
                }
                if (item.getErrorDetail() != null) {
                    String errorMsg = item.getErrorDetail().getMessage();
                    log.error("Push error: {}", errorMsg);
                    publishLog(finalBuildId, "error", "[ERROR] " + errorMsg);
                    pushError.append(errorMsg);
                }
            }
        };
        
        // ★ push 前先 docker login（刷新 Docker Daemon 的 Bearer Token）
        if (authConfig != null) {
            try {
                // 同时尝试带 http:// 和不带前缀两种格式
                dockerClient.authCmd().withAuthConfig(authConfig).exec();
                publishLog(buildId, "debug", "Registry 认证刷新成功 (http://)");
                log.info("Registry auth refreshed for push: {}", authConfig.getRegistryAddress());
            } catch (Exception e) {
                log.warn("Auth with http:// prefix failed, trying without: {}", e.getMessage());
                try {
                    // 回退：不带 http:// 前缀
                    String plainAddress = authConfig.getRegistryAddress().replace("http://", "");
                    AuthConfig fallbackAuth = new AuthConfig()
                        .withRegistryAddress(plainAddress)
                        .withUsername(config.getRegistryUsername())
                        .withPassword(config.getRegistryPassword());
                    dockerClient.authCmd().withAuthConfig(fallbackAuth).exec();
                    // 如果不带前缀的成功了，使用这个 authConfig 进行推送
                    authConfig = fallbackAuth;
                    publishLog(buildId, "debug", "Registry 认证刷新成功 (plain)");
                    log.info("Registry auth refreshed (plain) for push: {}", plainAddress);
                } catch (Exception e2) {
                    publishLog(buildId, "warn", "Registry 认证刷新失败: " + e2.getMessage());
                    log.warn("Failed to refresh registry auth: {}", e2.getMessage());
                }
            }
        }

        if (authConfig != null) {
            dockerClient.pushImageCmd(imageTag)
                .withAuthConfig(authConfig)
                .exec(callback)
                .awaitCompletion(config.getBuildTimeout(), TimeUnit.SECONDS);
        } else {
            dockerClient.pushImageCmd(imageTag)
                .exec(callback)
                .awaitCompletion(config.getBuildTimeout(), TimeUnit.SECONDS);
        }
        
        // 检查回调中是否捕获到错误
        if (pushError.length() > 0) {
            throw new RuntimeException("镜像推送失败: " + pushError);
        }
        
        publishLog(buildId, "info", "镜像推送完成: " + imageTag);
        log.info("Image pushed successfully: {}", imageTag);
    }

    private String extractRegistryHost(String registry) {
        if (!StringUtils.hasText(registry)) {
            return "";
        }
        String value = registry.trim();
        if (value.startsWith("http://")) {
            value = value.substring("http://".length());
        } else if (value.startsWith("https://")) {
            value = value.substring("https://".length());
        }
        int slash = value.indexOf("/");
        if (slash > 0) {
            return value.substring(0, slash);
        }
        return value;
    }
    
    /**
     * 删除本地镜像（推送成功后清理，避免磁盘堆积）
     */
    private void removeLocalImage(String imageTag, String buildId) {
        try {
            dockerClient.removeImageCmd(imageTag).withForce(false).withNoPrune(true).exec();
            publishLog(buildId, "info", "本地镜像已清理: " + imageTag);
            log.info("Local image removed: {}", imageTag);
        } catch (Exception e) {
            // 清理失败不影响构建结果，仅告警
            publishLog(buildId, "warn", "本地镜像清理失败（不影响部署）: " + e.getMessage());
            log.warn("Failed to remove local image {}: {}", imageTag, e.getMessage());
        }
    }

    /**
     * 周期清理本地构建镜像，并在 Kind 节点触发未使用镜像清理。
     */
    @Scheduled(cron = "${image-build.local-cleanup-cron:0 40 * * * *}")
    public void periodicLocalImageCleanup() {
        try {
            Map<String, Object> result = runLocalImageMaintenance("scheduled");
            log.info("Local image cleanup finished: {}", result);
        } catch (Exception e) {
            log.warn("Periodic local image cleanup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 立即执行一次本地镜像维护（供运维手动触发）。
     */
    public synchronized Map<String, Object> runLocalImageMaintenance(String triggerSource) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("triggerSource", StringUtils.hasText(triggerSource) ? triggerSource : "unknown");
        result.put("cleanupEnabled", config.isLocalCleanupEnabled());
        result.put("kindPruneEnabled", config.isKindPruneEnabled());
        result.put("retentionHours", Math.max(config.getLocalImageRetentionHours(), 1));
        result.put("kindClusterName", normalize(config.getKindClusterName()));

        if (!config.isLocalCleanupEnabled()) {
            result.put("removedLocalImages", 0);
            result.put("prunedKindNodes", 0);
            result.put("skipped", "image-build.local-cleanup-enabled=false");
            return result;
        }

        int removed = cleanupExpiredLocalBuildImages();
        result.put("removedLocalImages", removed);

        if (config.isKindPruneEnabled()) {
            int prunedNodes = pruneKindNodeImages();
            result.put("prunedKindNodes", prunedNodes);
        } else {
            result.put("prunedKindNodes", 0);
        }
        return result;
    }

    private int cleanupExpiredLocalBuildImages() {
        long nowMs = System.currentTimeMillis();
        long retentionMs = TimeUnit.HOURS.toMillis(Math.max(config.getLocalImageRetentionHours(), 1));
        Set<String> activeImageIds = collectActiveContainerImageIds();
        int removed = 0;

        List<Image> images = dockerClient.listImagesCmd().withShowAll(true).exec();
        for (Image image : images) {
            String imageId = image.getId();
            if (StringUtils.hasText(imageId) && activeImageIds.contains(imageId)) {
                continue;
            }
            long createdMs = resolveImageCreatedMillis(image);
            if (createdMs <= 0 || nowMs - createdMs < retentionMs) {
                continue;
            }
            String[] repoTags = image.getRepoTags();
            if (repoTags == null || repoTags.length == 0) {
                continue;
            }
            for (String tag : repoTags) {
                if (!shouldCleanupLocalTag(tag)) {
                    continue;
                }
                try {
                    dockerClient.removeImageCmd(tag).withForce(false).withNoPrune(true).exec();
                    removed++;
                    log.info("Removed expired local build image: {}", tag);
                } catch (Exception e) {
                    log.debug("Skip removing image {}: {}", tag, e.getMessage());
                }
            }
        }
        return removed;
    }

    private Set<String> collectActiveContainerImageIds() {
        Set<String> result = new HashSet<>();
        List<Container> running = dockerClient.listContainersCmd().withShowAll(false).exec();
        for (Container c : running) {
            if (StringUtils.hasText(c.getImageId())) {
                result.add(c.getImageId());
            }
        }
        return result;
    }

    private long resolveImageCreatedMillis(Image image) {
        try {
            if (image.getCreated() != null && image.getCreated() > 0) {
                return image.getCreated() * 1000;
            }
            if (!StringUtils.hasText(image.getId())) {
                return -1;
            }
            InspectImageResponse inspect = dockerClient.inspectImageCmd(image.getId()).exec();
            if (inspect != null && StringUtils.hasText(inspect.getCreated())) {
                return OffsetDateTime.parse(inspect.getCreated()).toInstant().toEpochMilli();
            }
        } catch (Exception e) {
            log.debug("Resolve image created time failed: image={}, err={}", image.getId(), e.getMessage());
        }
        return -1;
    }

    private boolean shouldCleanupLocalTag(String tag) {
        if (!StringUtils.hasText(tag) || "<none>:<none>".equals(tag)) {
            return false;
        }
        int idx = tag.lastIndexOf(':');
        String repo = idx > 0 ? tag.substring(0, idx) : tag;
        if (repo.startsWith("docker.io/library/")) {
            repo = repo.substring("docker.io/library/".length());
        }
        return LOCAL_BUILD_REPO_PATTERN.matcher(repo).matches();
    }

    private int pruneKindNodeImages() {
        List<Container> kindNodes = findKindNodeContainers();
        int prunedNodes = 0;
        for (Container node : kindNodes) {
            String nodeName = resolveContainerName(node);
            if (!StringUtils.hasText(nodeName)) {
                continue;
            }
            try {
                execInNode(node, "crictl", "rmi", "--prune");
                prunedNodes++;
                log.info("Pruned unused images on kind node: {}", nodeName);
            } catch (Exception e) {
                log.warn("Kind node image prune failed on {}: {}", nodeName, e.getMessage());
            }
        }
        return prunedNodes;
    }

    private void execInNode(Container node, String... cmd) throws InterruptedException {
        ExecCreateCmdResponse exec = dockerClient.execCreateCmd(node.getId())
            .withAttachStdout(true)
            .withAttachStderr(true)
            .withCmd(cmd)
            .exec();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame item) {
                try {
                    if (item != null && item.getPayload() != null) {
                        output.write(item.getPayload());
                    }
                } catch (IOException ignored) {
                    // 仅用于日志
                }
                super.onNext(item);
            }
        };
        dockerClient.execStartCmd(exec.getId())
            .exec(callback)
            .awaitCompletion(Math.max(config.getKindLoadTimeout(), 60), TimeUnit.SECONDS);
        InspectExecResponse inspect = dockerClient.inspectExecCmd(exec.getId()).exec();
        Long exit = inspect != null ? inspect.getExitCodeLong() : null;
        if (exit == null || exit != 0L) {
            throw new IllegalStateException("exec failed: cmd=" + String.join(" ", cmd)
                + ", exitCode=" + exit + ", output=" + output);
        }
    }
    
    /**
     * 清理构建上下文目录
     */
    private void cleanupBuildContext(Path contextPath) {
        deleteDirectory(contextPath);
    }
    
    /**
     * 清理残留的历史构建上下文
     * 扫描 BUILD_CONTEXT_DIR 下超过指定时间的目录
     */
    private void cleanupStaleContexts() {
        Path baseDir = Path.of(config.getBuildContextDir());
        if (!Files.exists(baseDir)) {
            return;
        }
        
        int staleHours = buildQueueConfig.getStaleContextHours();
        long staleThreshold = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(staleHours);
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, "build-*")) {
            for (Path dir : stream) {
                try {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    long lastModified = Files.getLastModifiedTime(dir).toMillis();
                    if (lastModified < staleThreshold) {
                        log.info("清理残留构建上下文: {} (超过 {} 小时)", dir, staleHours);
                        deleteDirectory(dir);
                    }
                } catch (IOException e) {
                    log.warn("检查目录时间失败: {}", dir, e);
                }
            }
        } catch (IOException e) {
            log.warn("扫描构建目录失败: {}", baseDir, e);
        }
    }
    
    /**
     * 删除目录及其内容
     */
    private void deleteDirectory(Path path) {
        try {
            Files.walk(path)
                .sorted((a, b) -> b.compareTo(a))  // 逆序，先删除文件再删除目录
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        log.warn("Failed to delete: {}", p);
                    }
                });
            log.debug("Build context cleaned: {}", path);
        } catch (IOException e) {
            log.warn("Failed to cleanup build context: {}", path, e);
        }
    }
    
    /**
     * 获取 Dockerfile 预览（用于调试）
     */
    public String previewDockerfile(String baseImage, Map<String, Object> envVars) {
        return generateDockerfile(baseImage, envVars);
    }
}
