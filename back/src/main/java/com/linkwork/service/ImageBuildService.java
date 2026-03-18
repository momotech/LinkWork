package com.linkwork.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.ImageBuildProperties;
import com.linkwork.model.dto.ImageBuildResult;
import com.linkwork.model.dto.ServiceBuildRequest;
import com.linkwork.model.enums.DeployMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class ImageBuildService {

    private static final Logger log = LoggerFactory.getLogger(ImageBuildService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final ImageBuildProperties properties;
    private final ObjectMapper objectMapper;

    public ImageBuildService(ImageBuildProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ImageBuildResult buildImages(ServiceBuildRequest request) {
        long start = System.currentTimeMillis();
        String serviceId = request.getServiceId();
        String buildId = request.getBuildId();
        log.info("Start image build: serviceId={}, buildId={}", serviceId, buildId);

        try {
            String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
            String registry = resolveRegistry(request);
            String baseImage = resolveAgentBaseImage(request);
            String imageTag = generateImageName(serviceId, timestamp, registry);

            Path contextPath = createBuildContext(serviceId, baseImage, request.getBuildEnvVars());
            try {
                buildImage(contextPath, imageTag);

                boolean pushed = false;
                if (properties.isPushEnabled()
                    && request.getDeployMode() == DeployMode.K8S
                    && StringUtils.hasText(registry)) {
                    dockerLoginIfNeeded(registry);
                    pushImage(imageTag);
                    pushed = true;
                    if (properties.isCleanupLocalAfterPush()) {
                        removeLocalImage(imageTag);
                    }
                }

                long duration = System.currentTimeMillis() - start;
                log.info("Image build finished: serviceId={}, imageTag={}, pushed={}, durationMs={}",
                    serviceId, imageTag, pushed, duration);
                return ImageBuildResult.success(imageTag, duration, pushed);
            } finally {
                cleanupBuildContext(contextPath);
                cleanupStaleContexts();
            }
        } catch (Exception ex) {
            log.error("Image build failed: serviceId={}, error={}", serviceId, ex.getMessage(), ex);
            return ImageBuildResult.failed(ex.getMessage());
        }
    }

    private String resolveRegistry(ServiceBuildRequest request) {
        if (StringUtils.hasText(request.getImageRegistry())) {
            return request.getImageRegistry();
        }
        return properties.getRegistry();
    }

    private String resolveAgentBaseImage(ServiceBuildRequest request) {
        if (StringUtils.hasText(request.getAgentBaseImage())) {
            return request.getAgentBaseImage();
        }
        return properties.getDefaultAgentBaseImage();
    }

    private String generateImageName(String serviceId, String timestamp, String registry) {
        String localImage = "service-" + serviceId + "-agent:" + serviceId + "-" + timestamp;
        if (!StringUtils.hasText(registry)) {
            return localImage;
        }
        return registry.replaceAll("/+$", "") + "/" + localImage;
    }

    private Path createBuildContext(String serviceId, String baseImage, Map<String, Object> envVars) throws IOException {
        Path baseDir = Path.of(properties.getBuildContextDir());
        Files.createDirectories(baseDir);
        Path contextDir = Files.createTempDirectory(baseDir, "build-" + serviceId + "-");

        String dockerfile = generateDockerfile(baseImage, envVars == null ? Map.of() : envVars);
        Files.writeString(contextDir.resolve("Dockerfile"), dockerfile, StandardCharsets.UTF_8);

        copyBuildScript(contextDir);
        copyConfigJson(contextDir);
        copyCedarPolicy(contextDir);
        return contextDir;
    }

    String generateDockerfile(String baseImage, Map<String, Object> envVars) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Auto-generated Dockerfile\n");
        sb.append("FROM ").append(baseImage).append("\n\n");

        if (envVars != null && !envVars.isEmpty()) {
            sb.append("# Build environment variables\n");
            for (Map.Entry<String, Object> entry : envVars.entrySet()) {
                if (!StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
                    continue;
                }
                sb.append("ENV ")
                    .append(entry.getKey())
                    .append("=")
                    .append(escapeEnvValue(toEnvString(entry.getValue())))
                    .append("\n");
            }
            sb.append("\n");
        }

        if (StringUtils.hasText(properties.getSdkRepoUrl())) {
            sb.append("ARG CACHEBUST=").append(System.currentTimeMillis()).append("\n");
            sb.append("RUN set -e \\\n");
            sb.append("    && git clone --depth 1 --single-branch -b ")
                .append(StringUtils.hasText(properties.getSdkRepoBranch()) ? properties.getSdkRepoBranch() : "main")
                .append(" ")
                .append(buildSdkCloneUrl())
                .append(" /tmp/_sdk_repo \\\n");
            sb.append("    && mkdir -p /opt/momo-agent-build/zzd-binaries /opt/momo-agent-build/sdk-source /opt/momo-agent-build/start-scripts \\\n");
            sb.append("    && if [ -d /tmp/_sdk_repo/docker/agent/zzd ]; then cp -a /tmp/_sdk_repo/docker/agent/zzd/. /opt/momo-agent-build/zzd-binaries/; fi \\\n");
            sb.append("    && if [ -d /tmp/_sdk_repo/momo-agent-sdk ]; then cp -a /tmp/_sdk_repo/momo-agent-sdk/. /opt/momo-agent-build/sdk-source/; fi \\\n");
            sb.append("    && if [ -d /tmp/_sdk_repo/docker/agent ]; then cp -f /tmp/_sdk_repo/docker/agent/start-*.sh /opt/momo-agent-build/start-scripts/ 2>/dev/null || true; fi \\\n");
            sb.append("    && rm -rf /tmp/_sdk_repo\n\n");
        }

        sb.append("RUN mkdir -p /opt/agent\n");
        sb.append("COPY config.json /opt/agent/config.json\n");
        sb.append("COPY cedar-policies/ /tmp/cedar-policies/\n");
        sb.append("COPY build.sh /build.sh\n");
        sb.append("RUN chmod +x /build.sh && /build.sh\n\n");
        sb.append("ENTRYPOINT [\"").append(properties.getEntrypointScript()).append("\"]\n");
        return sb.toString();
    }

    private String buildSdkCloneUrl() {
        String repoUrl = properties.getSdkRepoUrl();
        if (!StringUtils.hasText(repoUrl)) {
            return "";
        }
        String username = properties.getSdkRepoUsername();
        String password = properties.getSdkRepoPassword();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password) || !repoUrl.startsWith("https://")) {
            return repoUrl;
        }
        return repoUrl.replace("https://", "https://" + username + ":" + password + "@");
    }

    private String toEnvString(Object value) {
        if (value instanceof String str) {
            return str;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    String escapeEnvValue(String value) {
        if (value == null) {
            return "\"\"";
        }
        if (value.contains(" ") || value.contains("\"") || value.contains("$")
            || value.contains("\\") || value.contains("\n")) {
            return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\$")
                .replace("\n", "\\n") + "\"";
        }
        return value;
    }

    private void copyBuildScript(Path contextDir) throws IOException {
        Path target = contextDir.resolve("build.sh");
        ClassPathResource resource = new ClassPathResource("scripts/build.sh");
        if (resource.exists()) {
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, target);
                setExecutable(target);
                return;
            }
        }

        if (StringUtils.hasText(properties.getBuildScriptPath())) {
            Path source = Path.of(properties.getBuildScriptPath());
            if (Files.exists(source)) {
                Files.copy(source, target);
                setExecutable(target);
                return;
            }
        }

        Files.writeString(target, defaultBuildScript(), StandardCharsets.UTF_8);
        setExecutable(target);
    }

    private void copyConfigJson(Path contextDir) throws IOException {
        Path target = contextDir.resolve("config.json");
        ClassPathResource resource = new ClassPathResource("scripts/config.json");
        if (resource.exists()) {
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, target);
                return;
            }
        }
        Files.writeString(target, "{}", StandardCharsets.UTF_8);
    }

    private void copyCedarPolicy(Path contextDir) throws IOException {
        Path cedarDir = contextDir.resolve("cedar-policies");
        Files.createDirectories(cedarDir);
        ClassPathResource resource = new ClassPathResource("scripts/00-platform.cedar");
        if (resource.exists()) {
            try (InputStream inputStream = resource.getInputStream()) {
                Files.copy(inputStream, cedarDir.resolve("00-platform.cedar"));
            }
        }
    }

    private void buildImage(Path contextDir, String imageTag) throws IOException, InterruptedException {
        runCommand(
            List.of("docker", "build", "-t", imageTag, "."),
            contextDir,
            dockerEnv(),
            properties.getBuildTimeoutSeconds(),
            "docker build"
        );
    }

    private void pushImage(String imageTag) throws IOException, InterruptedException {
        runCommand(
            List.of("docker", "push", imageTag),
            null,
            dockerEnv(),
            properties.getBuildTimeoutSeconds(),
            "docker push"
        );
    }

    private void removeLocalImage(String imageTag) {
        try {
            runCommand(
                List.of("docker", "image", "rm", imageTag),
                null,
                dockerEnv(),
                120,
                "docker image rm"
            );
        } catch (Exception ex) {
            log.warn("Remove local image failed: imageTag={}, error={}", imageTag, ex.getMessage());
        }
    }

    private void dockerLoginIfNeeded(String registry) throws IOException, InterruptedException {
        if (!StringUtils.hasText(properties.getRegistryUsername()) || !StringUtils.hasText(properties.getRegistryPassword())) {
            return;
        }
        String registryHost = registry;
        int firstSlash = registryHost.indexOf('/');
        if (firstSlash > 0) {
            registryHost = registryHost.substring(0, firstSlash);
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
            "docker", "login", registryHost, "-u", properties.getRegistryUsername(), "--password-stdin"
        );
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().putAll(dockerEnv());
        Process process = processBuilder.start();
        try (OutputStream os = process.getOutputStream()) {
            os.write((properties.getRegistryPassword() + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        String output = readOutput(process.getInputStream());
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("docker login timeout");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("docker login failed: " + trimOutput(output));
        }
    }

    private Map<String, String> dockerEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        if (StringUtils.hasText(properties.getDockerHost())) {
            env.put("DOCKER_HOST", properties.getDockerHost());
        }
        if (StringUtils.hasText(properties.getRegistryUsername()) && StringUtils.hasText(properties.getRegistryPassword())) {
            String auth = Base64.getEncoder()
                .encodeToString((properties.getRegistryUsername() + ":" + properties.getRegistryPassword()).getBytes(StandardCharsets.UTF_8));
            env.put("LINKWORK_DOCKER_AUTH_B64", auth);
        }
        return env;
    }

    private void runCommand(List<String> command,
                            Path workDir,
                            Map<String, String> envVars,
                            int timeoutSeconds,
                            String actionName) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (workDir != null) {
            processBuilder.directory(workDir.toFile());
        }
        processBuilder.redirectErrorStream(true);
        if (envVars != null && !envVars.isEmpty()) {
            processBuilder.environment().putAll(envVars);
        }

        Process process = processBuilder.start();
        String output = readOutput(process.getInputStream());
        boolean finished = process.waitFor(Math.max(30, timeoutSeconds), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(actionName + " timeout");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(actionName + " failed: " + trimOutput(output));
        }
    }

    private String readOutput(InputStream inputStream) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    log.info("[docker] {}", line);
                }
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }

    private String trimOutput(String output) {
        if (!StringUtils.hasText(output)) {
            return "";
        }
        int max = 1500;
        String normalized = output.trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(normalized.length() - max);
    }

    private void cleanupBuildContext(Path contextPath) {
        if (contextPath == null || !Files.exists(contextPath)) {
            return;
        }
        try {
            Files.walk(contextPath)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ex) {
                        log.warn("Delete build context path failed: path={}, error={}", path, ex.getMessage());
                    }
                });
        } catch (IOException ex) {
            log.warn("Cleanup build context failed: path={}, error={}", contextPath, ex.getMessage());
        }
    }

    private void cleanupStaleContexts() {
        Path baseDir = Path.of(properties.getBuildContextDir());
        if (!Files.exists(baseDir)) {
            return;
        }
        long staleThreshold = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(6);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, "build-*")) {
            for (Path path : stream) {
                if (!Files.isDirectory(path)) {
                    continue;
                }
                long lastModified = Files.getLastModifiedTime(path).toMillis();
                if (lastModified < staleThreshold) {
                    cleanupBuildContext(path);
                }
            }
        } catch (IOException ex) {
            log.warn("Cleanup stale build contexts failed: {}", ex.getMessage());
        }
    }

    private void setExecutable(Path path) {
        try {
            path.toFile().setExecutable(true, false);
        } catch (Exception ignored) {
        }
    }

    private String defaultBuildScript() {
        return "#!/usr/bin/env bash\n"
            + "set -euo pipefail\n"
            + "mkdir -p /workspace /opt/agent\n"
            + "if [[ -n \"${MCP_CONFIG:-}\" ]]; then echo \"${MCP_CONFIG}\" > /opt/agent/mcp.json; fi\n"
            + "if [[ -n \"${SKILLS_CONFIG:-}\" ]]; then echo \"${SKILLS_CONFIG}\" > /opt/agent/skills.json; fi\n"
            + "echo \"linkwork build.sh finished\"\n";
    }
}
