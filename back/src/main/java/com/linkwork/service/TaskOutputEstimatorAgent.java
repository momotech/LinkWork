package com.linkwork.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.model.dto.TaskCreateRequest;
import com.linkwork.model.entity.RoleEntity;
import com.linkwork.model.enums.TaskOutputType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务产出预估 Agent
 *
 * 在任务执行前基于用户诉求、岗位 system prompt 与任务属性进行预估：
 * 1) 优先调用 LLM Gateway 输出结构化结果；
 * 2) LLM 不可用时，降级到规则引擎，避免阻断任务创建。
 */
@Slf4j
@Service
public class TaskOutputEstimatorAgent {

    private static final String DIALOG_CONCLUSION_CODE = TaskOutputType.DIALOG_CONCLUSION.getCode();
    private static final String GIT_BRANCH_CODE = TaskOutputType.GIT_BRANCH.getCode();
    private static final String DONE_TOKEN = "[DONE]";
    private static final int MAX_BRANCH_LENGTH = 64;
    private static final int MAX_PROMPT_SNIPPET = 1200;

    private static final Set<String> DIALOG_ONLY_KEYWORDS = Set.of(
            "只要结论", "仅回复", "仅对话", "不用文件", "无须文件", "不要文件", "只给建议",
            "only conclusion", "only answer", "dialog only", "no file output"
    );

    private static final Set<String> API_RESULT_KEYWORDS = Set.of(
            "api调用", "api call", "webhook", "callback", "回调", "curl", "http request", "openapi", "postman"
    );

    private static final Set<String> GIT_KEYWORDS = Set.of(
            "git", "branch", "commit", "push", "分支", "提交"
    );

    private static final Set<String> PULL_REQUEST_KEYWORDS = Set.of(
            "pull request", "merge request", "创建pr", "提pr", "提交pr", "pr链接", "mr链接"
    );

    private static final Set<String> EXCEL_KEYWORDS = Set.of(
            "excel", "xlsx", "xls", "sheet", "数据透视", "表格"
    );

    private static final Set<String> CSV_KEYWORDS = Set.of(
            "csv", "逗号分隔"
    );

    private static final Set<String> WORD_KEYWORDS = Set.of(
            "word", "doc", "docx", "技术文档", "文档"
    );

    private static final Set<String> PPT_KEYWORDS = Set.of(
            "ppt", "pptx", "slide", "slides", "演示文稿"
    );

    private static final Set<String> PDF_KEYWORDS = Set.of(
            "pdf"
    );

    private static final Set<String> MARKDOWN_KEYWORDS = Set.of(
            "markdown", "readme", "md文档", ".md"
    );

    private static final Set<String> TXT_KEYWORDS = Set.of(
            "txt", "纯文本", "text file", "日志文件"
    );

    private static final Set<String> PYTHON_KEYWORDS = Set.of(
            "python", "pandas", "jupyter", "notebook", ".py"
    );

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "java", "spring", "springboot", "maven", "gradle", "jvm"
    );

    private static final Set<String> JAVASCRIPT_KEYWORDS = Set.of(
            "javascript", "nodejs", "node.js", "frontend js", ".js"
    );

    private static final Set<String> TYPESCRIPT_KEYWORDS = Set.of(
            "typescript", "tsx", ".ts"
    );

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "sql", "ddl", "dml", "select ", "insert ", "update ", "delete "
    );

    private static final Set<String> SHELL_KEYWORDS = Set.of(
            "shell", "bash", "zsh", "sh脚本", "命令脚本"
    );

    private static final Set<String> CONFIG_KEYWORDS = Set.of(
            "配置文件", "yaml", "yml", "toml", "ini", "properties", ".env"
    );

    private static final Set<String> JSON_KEYWORDS = Set.of(
            "json", "jsonl"
    );

    private static final Set<String> PNG_KEYWORDS = Set.of(
            "png", "image", "chart", "plot", "graph", "可视化", "截图", "图表"
    );

    private static final Set<String> ARCHIVE_KEYWORDS = Set.of(
            "zip", "tar", "tar.gz", "压缩包", "归档"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${robot.output-estimator.llm.enabled:true}")
    private boolean llmEnabled;

    @Value("${robot.output-estimator.llm.gateway-url:}")
    private String gatewayUrl;

    @Value("${robot.output-estimator.llm.model:minimax-m2.1}")
    private String llmModel;

    @Value("${robot.output-estimator.llm.max-tokens:256}")
    private int llmMaxTokens;

    @Value("${robot.output-estimator.llm.stream:true}")
    private boolean llmStream;

    @Value("${robot.output-estimator.llm.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${robot.output-estimator.llm.read-timeout-ms:12000}")
    private int readTimeoutMs;

    @Value("${robot.output-estimator.llm.auth-token:}")
    private String authToken;

    @Value("${robot.output-estimator.llm.x-litellm-api-key:}")
    private String xLitellmApiKey;

    public record EstimateResult(List<String> estimatedOutput, String branchName, String source) {
        public EstimateResult {
            estimatedOutput = estimatedOutput == null ? List.of() : List.copyOf(estimatedOutput);
            source = StringUtils.hasText(source) ? source : "rule";
        }
    }

    public List<String> estimate(TaskCreateRequest request, RoleEntity role) {
        return estimateWithBranch(null, request, role).estimatedOutput();
    }

    public EstimateResult estimateWithBranch(String taskNo, TaskCreateRequest request, RoleEntity role) {
        String promptRaw = request != null ? request.getPrompt() : null;
        String prompt = normalize(promptRaw);
        String systemPrompt = normalize(role != null ? role.getPrompt() : null);
        String mergedText = prompt + "\n" + systemPrompt;

        if (containsAny(mergedText, DIALOG_ONLY_KEYWORDS)) {
            return new EstimateResult(List.of(DIALOG_CONCLUSION_CODE), null, "rule");
        }

        LinkedHashSet<TaskOutputType> ruleEstimated = new LinkedHashSet<>();
        addByTextRules(mergedText, ruleEstimated);
        addByTaskAttributes(request, ruleEstimated);

        if (ruleEstimated.isEmpty()) {
            ruleEstimated.add(TaskOutputType.DIALOG_CONCLUSION);
        }

        EstimateResult llmEstimate = estimateByLlm(taskNo, request, role, promptRaw);
        if (llmEstimate != null && !llmEstimate.estimatedOutput().isEmpty()) {
            LinkedHashSet<String> mergedOutputs = new LinkedHashSet<>(llmEstimate.estimatedOutput());
            LinkedHashSet<TaskOutputType> attributeOutputs = new LinkedHashSet<>();
            addByTaskAttributes(request, attributeOutputs);
            for (TaskOutputType type : attributeOutputs) {
                mergedOutputs.add(type.getCode());
            }
            normalizeDialogOutput(mergedOutputs);

            String branchName = llmEstimate.branchName();
            if (mergedOutputs.contains(GIT_BRANCH_CODE) && !StringUtils.hasText(branchName)) {
                branchName = buildFallbackBranchName(taskNo, promptRaw);
            }
            if (!mergedOutputs.contains(GIT_BRANCH_CODE)) {
                branchName = null;
            }
            return new EstimateResult(new ArrayList<>(mergedOutputs), branchName, "llm");
        }

        LinkedHashSet<String> fallbackOutputs = new LinkedHashSet<>(toCodes(ruleEstimated));
        normalizeDialogOutput(fallbackOutputs);
        String fallbackBranchName = fallbackOutputs.contains(GIT_BRANCH_CODE)
                ? buildFallbackBranchName(taskNo, promptRaw)
                : null;
        return new EstimateResult(new ArrayList<>(fallbackOutputs), fallbackBranchName, "rule");
    }

    private EstimateResult estimateByLlm(String taskNo, TaskCreateRequest request, RoleEntity role, String promptRaw) {
        if (!llmEnabled || !StringUtils.hasText(gatewayUrl)) {
            return null;
        }

        String token = resolveGatewayToken();
        if (!StringUtils.hasText(token)) {
            return null;
        }

        try {
            String systemPrompt = buildEstimatorSystemPrompt();
            String userPrompt = buildEstimatorUserPrompt(taskNo, request, role);
            String completion = callLlmGateway(systemPrompt, userPrompt, token, resolveEstimatorModel(request));
            if (!StringUtils.hasText(completion)) {
                return null;
            }
            return parseLlmEstimate(completion, taskNo, promptRaw);
        } catch (Exception e) {
            log.warn("LLM 产物预估失败，降级规则模式: taskNo={}, error={}", taskNo, e.getMessage());
            return null;
        }
    }

    private String callLlmGateway(String systemPrompt, String userPrompt, String token, String modelId) throws IOException, InterruptedException {
        JsonNode payload = buildGatewayPayload(systemPrompt, userPrompt, modelId);
        String requestBody = objectMapper.writeValueAsString(payload);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(connectTimeoutMs, 1000)))
                .build();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(gatewayUrl))
                .timeout(Duration.ofMillis(Math.max(readTimeoutMs, 2000)))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");

        if (StringUtils.hasText(xLitellmApiKey)) {
            requestBuilder.header("x-litellm-api-key", xLitellmApiKey.trim());
        }

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        if (llmStream) {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                String errorBody = readErrorBody(response.body());
                throw new IllegalStateException("LLM Gateway 请求失败: status=" + response.statusCode() + ", body=" + errorBody);
            }
            return readStreamCompletion(response.body());
        }

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("LLM Gateway 请求失败: status=" + response.statusCode() + ", body=" + response.body());
        }

        String text = extractTextFromGatewayPayload(response.body());
        return StringUtils.hasText(text) ? text : response.body();
    }

    private JsonNode buildGatewayPayload(String systemPrompt, String userPrompt, String modelId) {
        var root = objectMapper.createObjectNode();
        root.put("model", modelId);
        root.put("max_tokens", llmMaxTokens);
        root.put("stream", llmStream);

        var messages = root.putArray("messages");
        var system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt);

        var user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);
        return root;
    }

    private String readStreamCompletion(InputStream bodyStream) throws IOException {
        StringBuilder completion = new StringBuilder();
        StringBuilder raw = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(bodyStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                raw.append(line).append('\n');
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith(":")) {
                    continue;
                }
                if (trimmed.startsWith("data:")) {
                    trimmed = trimmed.substring(5).trim();
                }
                if (!StringUtils.hasText(trimmed) || DONE_TOKEN.equals(trimmed)) {
                    if (DONE_TOKEN.equals(trimmed)) {
                        break;
                    }
                    continue;
                }

                String delta = extractTextFromGatewayPayload(trimmed);
                if (StringUtils.hasText(delta)) {
                    completion.append(delta);
                }
            }
        }

        if (completion.length() > 0) {
            return completion.toString();
        }
        return raw.toString();
    }

    private String extractTextFromGatewayPayload(String payload) {
        if (!StringUtils.hasText(payload)) {
            return "";
        }

        String trimmed = payload.trim();
        if (!trimmed.startsWith("{")) {
            return trimmed;
        }

        try {
            JsonNode root = objectMapper.readTree(trimmed);
            StringBuilder text = new StringBuilder();

            appendTextNode(root.path("output_text"), text);
            appendTextNode(root.path("content"), text);

            JsonNode choices = root.path("choices");
            if (choices.isArray()) {
                for (JsonNode choice : choices) {
                    appendTextNode(choice.path("delta").path("content"), text);
                    appendTextNode(choice.path("message").path("content"), text);
                    appendTextNode(choice.path("text"), text);
                }
            }

            JsonNode data = root.path("data");
            if (data.isTextual()) {
                appendTextNode(data, text);
            } else if (data.isObject()) {
                appendTextNode(data.path("content"), text);
            }

            if (text.length() > 0) {
                return text.toString();
            }
            if (root.isTextual()) {
                return root.asText();
            }
            return "";
        } catch (Exception ignore) {
            return trimmed;
        }
    }

    private void appendTextNode(JsonNode node, StringBuilder builder) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }

        if (node.isTextual()) {
            builder.append(node.asText());
            return;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                appendTextNode(child, builder);
            }
            return;
        }

        if (node.isObject()) {
            appendTextNode(node.path("text"), builder);
            appendTextNode(node.path("content"), builder);
            appendTextNode(node.path("value"), builder);
        }
    }

    private EstimateResult parseLlmEstimate(String completion, String taskNo, String promptRaw) {
        String jsonText = extractJsonObject(completion);
        if (!StringUtils.hasText(jsonText)) {
            log.warn("LLM 产物预估解析失败（未找到 JSON）: taskNo={}, completion={}...", taskNo, truncate(completion, 240));
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(jsonText);
            LinkedHashSet<String> outputs = new LinkedHashSet<>();
            collectOutputCodes(root.path("estimatedOutput"), outputs);
            collectOutputCodes(root.path("estimated_output"), outputs);
            collectOutputCodes(root.path("outputs"), outputs);

            if (outputs.isEmpty()) {
                addAllowedCode(root.path("outputType").asText(null), outputs);
                addAllowedCode(root.path("output_type").asText(null), outputs);
            }

            String branchName = firstNonBlank(
                    root.path("branchName").asText(null),
                    root.path("branch_name").asText(null),
                    root.path("estimatedBranchName").asText(null)
            );
            branchName = sanitizeBranchName(branchName);

            if (StringUtils.hasText(branchName)) {
                outputs.add(GIT_BRANCH_CODE);
            }

            if (outputs.isEmpty()) {
                return null;
            }
            normalizeDialogOutput(outputs);

            if (outputs.contains(GIT_BRANCH_CODE) && !StringUtils.hasText(branchName)) {
                branchName = buildFallbackBranchName(taskNo, promptRaw);
            }
            if (!outputs.contains(GIT_BRANCH_CODE)) {
                branchName = null;
            }

            return new EstimateResult(new ArrayList<>(outputs), branchName, "llm");
        } catch (JsonProcessingException e) {
            log.warn("LLM 产物预估解析失败（JSON 非法）: taskNo={}, error={}", taskNo, e.getMessage());
            return null;
        }
    }

    private void collectOutputCodes(JsonNode node, LinkedHashSet<String> outputs) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    addAllowedCode(item.asText(), outputs);
                    continue;
                }
                addAllowedCode(item.path("code").asText(null), outputs);
                addAllowedCode(item.path("type").asText(null), outputs);
            }
            return;
        }

        if (node.isTextual()) {
            String text = node.asText();
            for (String piece : text.split("[,，\\s]+")) {
                addAllowedCode(piece, outputs);
            }
            return;
        }

        if (node.isObject()) {
            addAllowedCode(node.path("code").asText(null), outputs);
            addAllowedCode(node.path("type").asText(null), outputs);
        }
    }

    private void addAllowedCode(String code, LinkedHashSet<String> outputs) {
        if (!StringUtils.hasText(code)) {
            return;
        }
        TaskOutputType.fromCode(code.trim())
                .map(TaskOutputType::getCode)
                .ifPresent(outputs::add);
    }

    private void normalizeDialogOutput(LinkedHashSet<String> outputs) {
        if (outputs.size() > 1) {
            outputs.remove(DIALOG_CONCLUSION_CODE);
        }
        if (outputs.isEmpty()) {
            outputs.add(DIALOG_CONCLUSION_CODE);
        }
    }

    private String extractJsonObject(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }

        String trimmed = text.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstBrace = trimmed.indexOf('{');
            int lastBrace = trimmed.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return trimmed.substring(firstBrace, lastBrace + 1);
            }
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }

    private String buildEstimatorSystemPrompt() {
        String allowedOutputs = Arrays.stream(TaskOutputType.values())
                .map(type -> "- " + type.getCode() + ": " + type.getDescription())
                .collect(Collectors.joining("\\n"));

        return """
                你是任务产物预估助手，只负责判断任务执行后可能产出的结果类型。
                必须遵守以下规则：
                1) 只返回 JSON，不要 markdown，不要解释文字。
                2) JSON 字段固定为: estimatedOutput(array), branchName(string|null), reason(string)。
                3) estimatedOutput 只能使用下面白名单枚举，允许多个并存。
                4) 若只会给对话结论，则 estimatedOutput 仅保留 dialog_conclusion。
                5) 只有当预计有代码提交时才返回 branchName；否则 branchName 必须为 null。
                6) branchName 使用小写 kebab-case，建议格式 auto/{topic}-{taskShortId}。

                允许的 estimatedOutput:
                """ + allowedOutputs;
    }

    private String buildEstimatorUserPrompt(String taskNo, TaskCreateRequest request, RoleEntity role) throws JsonProcessingException {
        var root = objectMapper.createObjectNode();
        root.put("taskNo", taskNo == null ? "" : taskNo);
        root.put("userPrompt", truncate(request != null ? request.getPrompt() : null, MAX_PROMPT_SNIPPET));
        root.put("modelId", request != null ? request.getModelId() : "");

        var fileIds = root.putArray("fileIds");
        if (request != null && request.getFileIds() != null) {
            for (String fileId : request.getFileIds()) {
                fileIds.add(fileId);
            }
        }

        var roleNode = root.putObject("role");
        roleNode.put("roleId", role != null && role.getId() != null ? role.getId() : 0L);
        roleNode.put("roleName", role != null ? role.getName() : "");
        roleNode.put("rolePrompt", truncate(role != null ? role.getPrompt() : null, MAX_PROMPT_SNIPPET));

        var gitRepos = roleNode.putArray("gitRepos");
        if (role != null && role.getConfigJson() != null && role.getConfigJson().getGitRepos() != null) {
            for (RoleEntity.RoleConfig.GitRepo repo : role.getConfigJson().getGitRepos()) {
                var repoNode = gitRepos.addObject();
                repoNode.put("url", repo.getUrl());
                repoNode.put("branch", repo.getBranch());
            }
        }

        return "请基于以下任务要素做产物预估，按 system 要求返回 JSON:\n" +
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private String resolveEstimatorModel(TaskCreateRequest request) {
        if (request != null && StringUtils.hasText(request.getModelId())) {
            return request.getModelId().trim();
        }
        return llmModel;
    }

    private String resolveGatewayToken() {
        if (StringUtils.hasText(authToken)) {
            return authToken.trim();
        }
        return null;
    }

    private String readErrorBody(InputStream inputStream) {
        if (inputStream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildFallbackBranchName(String taskNo, String prompt) {
        if (!StringUtils.hasText(taskNo)) {
            return null;
        }

        String topic = slugify(prompt);
        if (!StringUtils.hasText(topic)) {
            topic = "task";
        }

        String taskShort = taskNo.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
        if (taskShort.length() > 8) {
            taskShort = taskShort.substring(taskShort.length() - 8);
        }

        return sanitizeBranchName("auto/" + topic + "-" + taskShort);
    }

    private String sanitizeBranchName(String branchName) {
        if (!StringUtils.hasText(branchName)) {
            return null;
        }

        String sanitized = branchName.trim().toLowerCase(Locale.ROOT)
                .replace('\\', '/')
                .replace(' ', '-');

        sanitized = sanitized.replaceAll("[^a-z0-9/_\\-.]+", "-");
        sanitized = sanitized.replaceAll("/{2,}", "/");
        sanitized = sanitized.replaceAll("-{2,}", "-");
        sanitized = sanitized.replaceAll("^/+", "");
        sanitized = sanitized.replaceAll("/+$", "");

        if (!StringUtils.hasText(sanitized)) {
            return null;
        }

        if (!sanitized.contains("/")) {
            sanitized = "auto/" + sanitized;
        }

        if (sanitized.length() > MAX_BRANCH_LENGTH) {
            sanitized = sanitized.substring(0, MAX_BRANCH_LENGTH);
            sanitized = sanitized.replaceAll("[-/]+$", "");
        }

        return StringUtils.hasText(sanitized) ? sanitized : null;
    }

    private String slugify(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }

        String slug = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");

        if (slug.length() > 24) {
            slug = slug.substring(0, 24).replaceAll("-+$", "");
        }
        return slug;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private void addByTextRules(String text, LinkedHashSet<TaskOutputType> estimated) {
        if (containsAny(text, GIT_KEYWORDS)) {
            estimated.add(TaskOutputType.GIT_BRANCH);
        }
        if (containsAny(text, PULL_REQUEST_KEYWORDS)) {
            estimated.add(TaskOutputType.PULL_REQUEST);
            estimated.add(TaskOutputType.GIT_BRANCH);
        }

        if (containsAny(text, PYTHON_KEYWORDS)) {
            estimated.add(TaskOutputType.PYTHON_FILE);
        }
        if (containsAny(text, JAVA_KEYWORDS)) {
            estimated.add(TaskOutputType.JAVA_FILE);
        }
        if (containsAny(text, JAVASCRIPT_KEYWORDS)) {
            estimated.add(TaskOutputType.JAVASCRIPT_FILE);
        }
        if (containsAny(text, TYPESCRIPT_KEYWORDS)) {
            estimated.add(TaskOutputType.TYPESCRIPT_FILE);
        }
        if (containsAny(text, SQL_KEYWORDS)) {
            estimated.add(TaskOutputType.SQL_FILE);
        }
        if (containsAny(text, SHELL_KEYWORDS)) {
            estimated.add(TaskOutputType.SHELL_SCRIPT);
        }
        if (containsAny(text, CONFIG_KEYWORDS)) {
            estimated.add(TaskOutputType.CONFIG_FILE);
        }

        if (containsAny(text, MARKDOWN_KEYWORDS)) {
            estimated.add(TaskOutputType.MARKDOWN);
        }
        if (containsAny(text, TXT_KEYWORDS)) {
            estimated.add(TaskOutputType.TXT);
        }
        if (containsAny(text, WORD_KEYWORDS)) {
            estimated.add(TaskOutputType.WORD);
        }
        if (containsAny(text, EXCEL_KEYWORDS)) {
            estimated.add(TaskOutputType.EXCEL);
        }
        if (containsAny(text, CSV_KEYWORDS)) {
            estimated.add(TaskOutputType.CSV);
        }
        if (containsAny(text, PPT_KEYWORDS)) {
            estimated.add(TaskOutputType.PPT);
        }
        if (containsAny(text, PDF_KEYWORDS)) {
            estimated.add(TaskOutputType.PDF);
        }

        if (containsAny(text, JSON_KEYWORDS)) {
            estimated.add(TaskOutputType.JSON);
        }
        if (containsAny(text, PNG_KEYWORDS)) {
            estimated.add(TaskOutputType.PNG);
        }
        if (containsAny(text, ARCHIVE_KEYWORDS)) {
            estimated.add(TaskOutputType.ARCHIVE);
        }

        if (containsAny(text, API_RESULT_KEYWORDS)) {
            estimated.add(TaskOutputType.API_CALL_RESULT);
        }
    }

    private void addByTaskAttributes(TaskCreateRequest request, LinkedHashSet<TaskOutputType> estimated) {
        if (request != null && request.getFileIds() != null) {
            for (String fileId : request.getFileIds()) {
                TaskOutputType.fromFileName(fileId).ifPresent(estimated::add);
            }
        }
    }

    private List<String> toCodes(LinkedHashSet<TaskOutputType> estimated) {
        List<String> output = new ArrayList<>(estimated.size());
        for (TaskOutputType type : estimated) {
            output.add(type.getCode());
        }
        return output;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, Set<String> keywords) {
        if (text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
