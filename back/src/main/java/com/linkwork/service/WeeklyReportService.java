package com.linkwork.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 读取 git 提交记录并生成周报。
 */
@Slf4j
@Service
public class WeeklyReportService {

    private static final ZoneId ZONE_ID = ZoneId.systemDefault();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter GIT_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DONE_TOKEN = "[DONE]";

    private static final List<ThemeRule> THEME_RULES = List.of(
            new ThemeRule("任务执行与终止链路", Pattern.compile("task|任务|mission|terminate|终止|dispatch|websocket|stream|redis", Pattern.CASE_INSENSITIVE)),
            new ThemeRule("审批与风控能力", Pattern.compile("approval|审批|risk|风控|\\bip\\b|\\bipv4\\b|\\bipv6\\b", Pattern.CASE_INSENSITIVE)),
            new ThemeRule("GitLab 认证与权限", Pattern.compile("gitlab|oauth|auth|认证|权限", Pattern.CASE_INSENSITIVE)),
            new ThemeRule("部署与环境治理", Pattern.compile("deploy|部署|docker|compose|prod|robot_env|environment", Pattern.CASE_INSENSITIVE)),
            new ThemeRule("前端体验与可读性", Pattern.compile("front|ui|style|layout|sidebar|table|readability|看板|动画|交互", Pattern.CASE_INSENSITIVE)),
            new ThemeRule("文档与规范", Pattern.compile("docs|文档|guideline|spec", Pattern.CASE_INSENSITIVE))
    );

    private static final List<KeywordRule> KEYWORD_RULES = List.of(
            new KeywordRule("任务终止", Pattern.compile("terminate|终止", Pattern.CASE_INSENSITIVE)),
            new KeywordRule("审批风控", Pattern.compile("approval|审批|risk|风控", Pattern.CASE_INSENSITIVE)),
            new KeywordRule("GitLab认证", Pattern.compile("gitlab|oauth|认证|auth", Pattern.CASE_INSENSITIVE)),
            new KeywordRule("Redis队列", Pattern.compile("redis|queue|stream", Pattern.CASE_INSENSITIVE)),
            new KeywordRule("部署发布", Pattern.compile("deploy|部署|docker|compose", Pattern.CASE_INSENSITIVE)),
            new KeywordRule("Runtime模式", Pattern.compile("runtime|sidecar|alone", Pattern.CASE_INSENSITIVE)),
            new KeywordRule("输出估算", Pattern.compile("estimate|estimation|taxonomy|报告", Pattern.CASE_INSENSITIVE)),
            new KeywordRule("前端可读性", Pattern.compile("readability|table|layout|sidebar|ui|style", Pattern.CASE_INSENSITIVE)),
            new KeywordRule("安全策略", Pattern.compile("security|安全|jwt|策略", Pattern.CASE_INSENSITIVE))
    );

    private static final Map<String, String> KEYWORD_PHRASE_MAP = Map.ofEntries(
            Map.entry("审批风控", "审批流中心全栈联通"),
            Map.entry("任务终止", "任务终止链路闭环（API-队列-流式回显）"),
            Map.entry("GitLab认证", "GitLab 认证稳定性与生产部署能力提升"),
            Map.entry("Redis队列", "任务队列与流式事件联调收敛"),
            Map.entry("部署发布", "生产部署流程统一与环境治理升级"),
            Map.entry("Runtime模式", "Sidecar/Alone 运行模式能力贯通"),
            Map.entry("输出估算", "任务产出估算与报告自动化"),
            Map.entry("前端可读性", "执行流与看板 UI 可读性专项优化"),
            Map.entry("安全策略", "风险 IP 审计能力补齐")
    );

    private static final Map<String, String> AUTHOR_KEYWORD_PHRASE_MAP = Map.ofEntries(
            Map.entry("审批风控", "审批流与风险控制能力建设"),
            Map.entry("任务终止", "任务终止与执行链路优化"),
            Map.entry("GitLab认证", "GitLab 认证与权限治理"),
            Map.entry("Redis队列", "Redis 队列与流式通道联调"),
            Map.entry("部署发布", "部署发布流程优化"),
            Map.entry("Runtime模式", "Runtime 模式能力建设"),
            Map.entry("输出估算", "任务产出估算与报告能力"),
            Map.entry("前端可读性", "前端交互与可读性优化"),
            Map.entry("安全策略", "安全策略与认证治理")
    );

    private static final Map<String, String> AUTHOR_ALIAS_MAP = Map.of();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${robot.weekly-report.repo-path:}")
    private String configuredRepoPath;

    @Value("${robot.weekly-report.llm.enabled:true}")
    private boolean llmEnabled;

    @Value("${robot.weekly-report.llm.gateway-url:}")
    private String llmGatewayUrl;

    @Value("${robot.weekly-report.llm.model:minimax-m2.1}")
    private String llmModel;

    @Value("${robot.weekly-report.llm.max-tokens:900}")
    private int llmMaxTokens;

    @Value("${robot.weekly-report.llm.stream:true}")
    private boolean llmStream;

    @Value("${robot.weekly-report.llm.connect-timeout-ms:3000}")
    private int llmConnectTimeoutMs;

    @Value("${robot.weekly-report.llm.read-timeout-ms:12000}")
    private int llmReadTimeoutMs;

    @Value("${robot.weekly-report.llm.auth-token:}")
    private String llmAuthToken;

    @Value("${robot.weekly-report.llm.x-litellm-api-key:}")
    private String xLitellmApiKey;

    public String buildCurrentWeekMarkdown() {
        DateWindow window = currentWeekWindow();
        WeeklyReportData data = collectWeeklyData(window);

        String fallbackMarkdown = renderRuleMarkdown(data);
        String llmMarkdown = renderLlmMarkdown(data);
        if (StringUtils.hasText(llmMarkdown)) {
            return llmMarkdown;
        }
        return fallbackMarkdown;
    }

    private WeeklyReportData collectWeeklyData(DateWindow window) {
        List<String> branches = listAllBranches();
        Map<String, Map<String, Long>> authorBranches = collectAuthorBranches(branches, window);
        List<AuthorBranchStat> authorStats = buildAuthorStats(authorBranches);
        List<CommitRecord> allCommits = listAllCommits(window);
        int mergeCommitCount = countMergeCommits(window);
        DiffStats diffStats = collectDiffStats(window);
        List<ModuleCount> focusModules = summarizeFocusModules(window);

        Set<String> allAuthors = allCommits.stream()
                .map(CommitRecord::author)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<ThemeStat> themes = summarizeThemes(allCommits);
        List<KeywordCount> keywords = summarizeKeywords(allCommits);
        List<AuthorContribution> authorContributions = buildAuthorContributions(allCommits);

        return new WeeklyReportData(
                window.startDate(),
                window.endDate(),
                allCommits.size(),
                mergeCommitCount,
                diffStats.addedLines(),
                diffStats.deletedLines(),
                allAuthors,
                countActiveBranches(authorStats),
                focusModules,
                authorContributions,
                themes,
                keywords
        );
    }

    private String renderRuleMarkdown(WeeklyReportData data) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("### 本周周报（%s ~ %s）\n\n",
                data.startDate().format(DATE_FMT),
                data.endDate().format(DATE_FMT)));

        sb.append("#### 本周提交作者与产出（按作者分类）\n\n");
        if (data.authorContributions().isEmpty()) {
            sb.append("- 本周暂无提交记录\n\n");
        } else {
            for (AuthorContribution author : data.authorContributions()) {
                sb.append(String.format("**%s**（%d）\n", author.author(), author.commitCount()));
                for (String output : author.outputs()) {
                    sb.append(String.format("- %s\n", output));
                }
                sb.append("\n");
            }
        }

        sb.append("#### 本周工作周报\n\n");
        List<String> weeklyBullets = buildWeeklyBullets(data);
        for (String bullet : weeklyBullets) {
            sb.append("- ").append(bullet).append("\n");
        }
        sb.append("\n");

        sb.append("#### 本周产出关键词\n\n");
        List<String> outputKeywords = buildOutputKeywords(data);
        if (outputKeywords.isEmpty()) {
            sb.append("- 本周暂无可沉淀的关键词产出\n");
        } else {
            for (String keyword : outputKeywords) {
                sb.append("- ").append(keyword).append("\n");
            }
        }

        return sb.toString();
    }

    private List<String> buildWeeklyBullets(WeeklyReportData data) {
        List<String> bullets = new ArrayList<>();
        bullets.add(buildSummaryBullet(data));

        if (data.themeStats().isEmpty()) {
            bullets.add("本周以零散修复为主，暂无可聚合主题。");
            return bullets;
        }

        List<ThemeStat> prioritized = data.themeStats().stream()
                .filter(theme -> !"文档与规范".equals(theme.name()))
                .limit(5)
                .toList();
        if (prioritized.isEmpty()) {
            prioritized = data.themeStats().stream().limit(5).toList();
        }

        for (ThemeStat theme : prioritized) {
            bullets.add(buildThemeBullet(theme));
        }
        return bullets;
    }

    private String buildSummaryBullet(WeeklyReportData data) {
        String modulePhrase = formatFocusModules(data.focusModules());
        return String.format("本周共提交 %d 个非合并 commit（另有 %d 个 merge），累计变更约 +%d/-%d 行，重点集中在 %s。",
                data.commitCount(),
                data.mergeCommitCount(),
                data.addedLines(),
                data.deletedLines(),
                modulePhrase);
    }

    private String formatFocusModules(List<ModuleCount> modules) {
        if (modules == null || modules.isEmpty()) {
            return "多个核心模块";
        }
        List<String> names = modules.stream().map(ModuleCount::module).toList();
        if (names.size() == 1) {
            return names.get(0) + " 模块";
        }
        if (names.size() == 2) {
            return String.join("、", names) + " 两块";
        }
        return String.join("、", names) + " 三块";
    }

    private String buildThemeBullet(ThemeStat theme) {
        String detail = themeDetailHint(theme.name());
        return switch (theme.name()) {
            case "任务执行与终止链路" -> String.format("完成“任务与执行链路”核心能力：%s（相关提交 %d 条）。", detail, theme.count());
            case "审批与风控能力" -> String.format("完成“风险治理与审计”建设：%s（相关提交 %d 条）。", detail, theme.count());
            case "GitLab 认证与权限" -> String.format("完成“GitLab 认证与权限”增强：%s（相关提交 %d 条）。", detail, theme.count());
            case "部署与环境治理" -> String.format("完善“部署与环境治理”能力：%s（相关提交 %d 条）。", detail, theme.count());
            case "前端体验与可读性" -> String.format("持续优化“前端体验与可读性”：%s（相关提交 %d 条）。", detail, theme.count());
            case "文档与规范" -> String.format("同步“文档与规范”沉淀：%s（相关提交 %d 条）。", detail, theme.count());
            default -> String.format("推进“%s”：%s（相关提交 %d 条）。", theme.name(), detail, theme.count());
        };
    }

    private String themeDetailHint(String themeName) {
        return switch (themeName) {
            case "任务执行与终止链路" -> "覆盖任务终止 API、Redis 队列联动、WebSocket 流式状态同步与执行稳定性修复";
            case "审批与风控能力" -> "覆盖审批流联动、风险等级展示、风险 IP 记录与审计链路补齐";
            case "GitLab 认证与权限" -> "覆盖认证前置校验、读写权限区分、认证提示与失败重试优化";
            case "部署与环境治理" -> "覆盖 ROBOT_ENV 分层、部署脚本统一、镜像与配置路径标准化";
            case "前端体验与可读性" -> "覆盖任务流状态展示、表格可读性提升、侧边栏与详情交互优化";
            case "文档与规范" -> "覆盖 API 设计、任务进展追踪与实施规范同步";
            default -> "覆盖核心功能迭代与稳定性改进";
        };
    }

    private String normalizeCommitSubject(String subject) {
        if (!StringUtils.hasText(subject)) {
            return "";
        }
        String cleaned = subject.trim();
        cleaned = cleaned.replaceFirst("^\\[[^]]+\\]\\s*", "");
        cleaned = cleaned.replaceFirst("^[A-Za-z]+\\([^)]*\\):\\s*", "");
        cleaned = cleaned.replaceFirst("^[A-Za-z]+:\\s*", "");
        cleaned = cleaned.replaceFirst("^(?i)(fix|feat|chore|docs|refactor|style|test|ci|build|perf|revert)\\s+", "");
        cleaned = cleaned.replaceFirst("^[，,；;。\\s]+", "");
        cleaned = cleaned.replaceFirst("[。;；\\s]+$", "");
        return truncate(cleaned, 42);
    }


    private String truncate(String value, int maxLen) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLen ? trimmed.substring(0, maxLen) + "..." : trimmed;
    }

    private List<String> buildOutputKeywords(WeeklyReportData data) {
        List<String> keywords = new ArrayList<>();
        for (KeywordCount keyword : data.keywordCounts()) {
            if (keyword.count() < 2) {
                continue;
            }
            String phrase = KEYWORD_PHRASE_MAP.get(keyword.keyword());
            if (StringUtils.hasText(phrase) && !keywords.contains(phrase)) {
                keywords.add(phrase);
            }
            if (keywords.size() >= 5) {
                return keywords;
            }
        }

        if (keywords.size() < 5) {
            for (ThemeStat theme : data.themeStats()) {
                String fallback = switch (theme.name()) {
                    case "任务执行与终止链路" -> "任务与终止链路稳定性提升";
                    case "审批与风控能力" -> "审批风险治理能力持续增强";
                    case "GitLab 认证与权限" -> "GitLab 认证与权限治理完善";
                    case "部署与环境治理" -> "部署流程与环境配置标准化";
                    case "前端体验与可读性" -> "前端交互与可读性专项优化";
                    default -> null;
                };
                if (StringUtils.hasText(fallback) && !keywords.contains(fallback)) {
                    keywords.add(fallback);
                }
                if (keywords.size() >= 5) {
                    break;
                }
            }
        }

        return keywords;
    }

    private String renderLlmMarkdown(WeeklyReportData data) {
        if (data.commitCount() == 0) {
            return null;
        }
        if (!llmEnabled || !StringUtils.hasText(llmGatewayUrl) || !StringUtils.hasText(llmAuthToken)) {
            return null;
        }

        try {
            String systemPrompt = buildLlmSystemPrompt();
            String userPrompt = buildLlmUserPrompt(data);
            String completion = callLlmGateway(systemPrompt, userPrompt);
            String markdown = cleanupLlmMarkdown(completion);
            if (!StringUtils.hasText(markdown)) {
                return null;
            }
            if (!isValidWeeklyReportMarkdown(markdown, data)) {
                log.warn("LLM 周报格式校验失败，降级规则输出");
                return null;
            }
            return markdown;
        } catch (Exception e) {
            log.warn("LLM 周报生成失败，降级规则输出: {}", e.getMessage());
            return null;
        }
    }

    private String buildLlmSystemPrompt() {
        return """
                你是研发团队周报助手。请根据输入 JSON 生成“信息密度高、可直接群发”的 Markdown。
                输出要求：
                1) 仅输出 Markdown 正文，不要解释，不要代码块。
                2) 必须包含且仅包含以下三级标题：
                   - #### 本周提交作者与产出（按作者分类）
                   - #### 本周工作周报
                   - #### 本周产出关键词
                3) 第二节“本周工作周报”必须使用 5~7 条列表，且第一条固定为统计总览句，句式参考：
                   本周共提交 X 个非合并 commit（另有 Y 个 merge），累计变更约 +A/-D 行，重点集中在 M1、M2、M3 三块。
                4) 第二节其余条目要按主题写“完成/推进/优化 + 具体事项”，不得只写“若干优化/持续迭代”等空话。
                5) 第三节“本周产出关键词”输出 5 条左右短语，不带计数。
                6) 第一节按作者分组，仅列举作者本周代表提交产出，不展示分支列表。
                7) 第一节按提交量控制篇幅：commit>=100 输出 6~8 条，commit>=50 输出 4~6 条，其余输出 2~4 条；禁止流水账式逐条罗列。
                8) 全文尽量使用中文表达，英文仅保留必要技术名词（如 GitLab、Redis、WebSocket），避免生硬中英混写。
                9) 数据必须严格来自输入，不得编造作者、分支、提交量。
                """;
    }

    private String buildLlmUserPrompt(WeeklyReportData data) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("weekStart", data.startDate().format(DATE_FMT));
        payload.put("weekEnd", data.endDate().format(DATE_FMT));
        payload.put("commitCount", data.commitCount());
        payload.put("mergeCommitCount", data.mergeCommitCount());
        payload.put("addedLines", data.addedLines());
        payload.put("deletedLines", data.deletedLines());
        payload.put("authorCount", data.authors().size());
        payload.put("authors", data.authors());
        payload.put("activeBranchCount", data.activeBranchCount());

        List<Map<String, Object>> focusModules = new ArrayList<>();
        for (ModuleCount module : data.focusModules()) {
            Map<String, Object> moduleObj = new LinkedHashMap<>();
            moduleObj.put("module", module.module());
            moduleObj.put("count", module.count());
            focusModules.add(moduleObj);
        }
        payload.put("focusModules", focusModules);

        List<Map<String, Object>> authors = new ArrayList<>();
        for (AuthorContribution author : data.authorContributions()) {
            Map<String, Object> authorObj = new LinkedHashMap<>();
            authorObj.put("author", author.author());
            authorObj.put("commitCount", author.commitCount());
            authorObj.put("minOutputCount", minOutputsForAuthor(author.commitCount()));
            authorObj.put("maxOutputCount", maxOutputsForAuthor(author.commitCount()));
            authorObj.put("outputs", author.outputs());
            authors.add(authorObj);
        }
        payload.put("authorContributions", authors);

        List<Map<String, Object>> themes = new ArrayList<>();
        for (ThemeStat theme : data.themeStats()) {
            Map<String, Object> themeObj = new LinkedHashMap<>();
            themeObj.put("theme", theme.name());
            themeObj.put("count", theme.count());
            themeObj.put("summaryHint", themeDetailHint(theme.name()));
            themes.add(themeObj);
        }
        payload.put("weeklyThemes", themes);

        List<Map<String, Object>> keywords = new ArrayList<>();
        for (KeywordCount keyword : data.keywordCounts()) {
            Map<String, Object> keywordObj = new LinkedHashMap<>();
            keywordObj.put("keyword", keyword.keyword());
            keywordObj.put("count", keyword.count());
            keywordObj.put("phraseHint", KEYWORD_PHRASE_MAP.get(keyword.keyword()));
            keywords.add(keywordObj);
        }
        payload.put("keywords", keywords);
        payload.put("ruleWeeklyBullets", buildWeeklyBullets(data));
        payload.put("ruleKeywordBullets", buildOutputKeywords(data));

        return "请严格按 system 要求输出周报 Markdown，输入数据如下：\n" +
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
    }

    private String callLlmGateway(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        JsonNode requestBody = buildLlmRequestPayload(systemPrompt, userPrompt);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(Math.max(llmConnectTimeoutMs, 1000)))
                .build();

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(llmGatewayUrl))
                .timeout(java.time.Duration.ofMillis(Math.max(llmReadTimeoutMs, 2000)))
                .header("Authorization", "Bearer " + llmAuthToken.trim())
                .header("Content-Type", "application/json");

        if (StringUtils.hasText(xLitellmApiKey)) {
            requestBuilder.header("x-litellm-api-key", xLitellmApiKey.trim());
        }

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
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

    private JsonNode buildLlmRequestPayload(String systemPrompt, String userPrompt) {
        var root = objectMapper.createObjectNode();
        root.put("model", llmModel);
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

    private String cleanupLlmMarkdown(String completion) {
        if (!StringUtils.hasText(completion)) {
            return null;
        }
        String markdown = completion.trim();
        if (markdown.startsWith("```")) {
            markdown = markdown.replaceFirst("^```[a-zA-Z]*\\s*", "");
            markdown = markdown.replaceFirst("\\s*```$", "");
        }
        return markdown.trim();
    }

    private boolean isValidWeeklyReportMarkdown(String markdown, WeeklyReportData data) {
        String lower = markdown.toLowerCase(Locale.ROOT);
        boolean hasSections = lower.contains("本周提交作者与产出")
                && lower.contains("本周工作周报")
                && lower.contains("本周产出关键词");
        if (!hasSections) {
            return false;
        }

        boolean hasSummarySentence = markdown.contains("本周共提交")
                && markdown.contains(String.valueOf(data.commitCount()))
                && markdown.contains(String.valueOf(data.mergeCommitCount()));
        int workBullets = countSectionBullets(markdown, "#### 本周工作周报", "#### 本周产出关键词");
        int keywordBullets = countSectionBullets(markdown, "#### 本周产出关键词", null);
        return hasSummarySentence && workBullets >= 5 && keywordBullets >= 4;
    }

    private int countSectionBullets(String markdown, String startTitle, String endTitle) {
        int start = markdown.indexOf(startTitle);
        if (start < 0) {
            return 0;
        }
        int from = start + startTitle.length();
        int end = endTitle == null ? markdown.length() : markdown.indexOf(endTitle, from);
        if (end < 0) {
            end = markdown.length();
        }

        int count = 0;
        for (String line : markdown.substring(from, end).split("\n")) {
            if (line.trim().startsWith("- ")) {
                count++;
            }
        }
        return count;
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

    private DateWindow currentWeekWindow() {
        LocalDate today = LocalDate.now(ZONE_ID);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        LocalDateTime since = monday.atStartOfDay();
        LocalDateTime until = sunday.plusDays(1).atStartOfDay();
        return new DateWindow(monday, sunday, since, until);
    }

    private List<String> listAllBranches() {
        List<String> remoteBranches = runGit(List.of("git", "for-each-ref", "--format=%(refname:short)", "refs/remotes/origin"));
        List<String> normalizedRemote = remoteBranches.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .filter(s -> !"origin/HEAD".equals(s))
                .sorted()
                .toList();
        if (!normalizedRemote.isEmpty()) {
            return normalizedRemote;
        }

        List<String> localBranches = runGit(List.of("git", "for-each-ref", "--format=%(refname:short)", "refs/heads"));
        return localBranches.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .sorted()
                .toList();
    }

    private Map<String, Map<String, Long>> collectAuthorBranches(List<String> branches, DateWindow window) {
        Map<String, Map<String, Long>> result = new HashMap<>();
        String since = formatGitTime(window.sinceInclusive());
        String until = formatGitTime(window.untilExclusive());

        for (String branch : branches) {
            List<String> lines = runGit(List.of(
                    "git", "log", branch,
                    "--first-parent",
                    "--since=" + since,
                    "--until=" + until,
                    "--no-merges",
                    "--pretty=format:%an"
            ));

            if (lines.isEmpty()) {
                continue;
            }

            Map<String, Long> authorCount = lines.stream()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

            String displayBranch = normalizeBranchName(branch);
            authorCount.forEach((author, count) -> result
                    .computeIfAbsent(author, k -> new HashMap<>())
                    .merge(displayBranch, count, Long::sum));
        }

        return result;
    }

    private List<AuthorBranchStat> buildAuthorStats(Map<String, Map<String, Long>> authorBranches) {
        return authorBranches.entrySet().stream()
                .map(entry -> {
                    String author = entry.getKey();
                    List<BranchCount> branches = entry.getValue().entrySet().stream()
                            .map(e -> new BranchCount(e.getKey(), e.getValue()))
                            .sorted(Comparator.comparingLong(BranchCount::count).reversed()
                                    .thenComparing(BranchCount::branch))
                            .toList();
                    long total = branches.stream().mapToLong(BranchCount::count).sum();
                    return new AuthorBranchStat(author, total, branches);
                })
                .sorted(Comparator.comparingLong(AuthorBranchStat::totalCommits).reversed()
                        .thenComparing(AuthorBranchStat::author))
                .toList();
    }

    private List<AuthorContribution> buildAuthorContributions(List<CommitRecord> commits) {
        Map<String, Integer> commitCountByAuthor = new HashMap<>();
        Map<String, List<String>> subjectsByAuthor = new HashMap<>();

        for (CommitRecord commit : commits) {
            String author = canonicalAuthorName(commit.author());
            commitCountByAuthor.merge(author, 1, Integer::sum);
            subjectsByAuthor.computeIfAbsent(author, k -> new ArrayList<>()).add(commit.subject());
        }

        return commitCountByAuthor.entrySet().stream()
                .map(entry -> {
                    String author = entry.getKey();
                    List<String> outputs = summarizeAuthorOutputs(subjectsByAuthor.getOrDefault(author, List.of()), entry.getValue());
                    return new AuthorContribution(author, entry.getValue(), outputs);
                })
                .sorted(Comparator.comparingInt(AuthorContribution::commitCount).reversed()
                        .thenComparing(AuthorContribution::author))
                .toList();
    }

    private List<String> summarizeAuthorOutputs(List<String> subjects, int commitCount) {
        if (subjects == null || subjects.isEmpty()) {
            return List.of("常规功能迭代与缺陷修复");
        }

        int maxOutputs = maxOutputsForAuthor(commitCount);
        int minOutputs = minOutputsForAuthor(commitCount);

        Set<String> outputSet = new LinkedHashSet<>();
        for (String subject : subjects) {
            String output = toLocalizedAuthorOutput(subject);
            if (!StringUtils.hasText(output)) {
                continue;
            }
            outputSet.add(output);
            if (outputSet.size() >= maxOutputs) {
                break;
            }
        }

        List<String> outputs = new ArrayList<>(outputSet);

        Map<String, Long> phraseCounts = new HashMap<>();
        for (KeywordRule rule : KEYWORD_RULES) {
            long count = subjects.stream()
                    .filter(StringUtils::hasText)
                    .filter(subject -> rule.pattern().matcher(subject).find())
                    .count();
            if (count <= 0) {
                continue;
            }
            String phrase = AUTHOR_KEYWORD_PHRASE_MAP.getOrDefault(rule.name(), rule.name());
            phraseCounts.merge(phrase, count, Long::sum);
        }

        List<String> phraseOutputs = phraseCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();

        if (outputs.size() < minOutputs) {
            for (String phrase : phraseOutputs) {
                if (outputs.size() >= maxOutputs) {
                    break;
                }
                if (!outputs.contains(phrase)) {
                    outputs.add(phrase);
                }
                if (outputs.size() >= minOutputs) {
                    break;
                }
            }
        }

        if (!outputs.isEmpty()) {
            return outputs.stream().limit(maxOutputs).toList();
        }

        if (!phraseOutputs.isEmpty()) {
            return phraseOutputs.stream().limit(maxOutputs).toList();
        }

        return List.of("常规功能迭代与缺陷修复");
    }

    private String toLocalizedAuthorOutput(String subject) {
        String normalized = normalizeCommitSubject(subject);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }

        if (containsChinese(normalized)) {
            return truncate(normalized, 42);
        }

        return translateEnglishSubject(subject, normalized);
    }

    private String translateEnglishSubject(String rawSubject, String normalizedSubject) {
        String lower = rawSubject == null ? "" : rawSubject.toLowerCase(Locale.ROOT);

        if (lower.contains("weekly-report") || lower.contains("weekly report")) {
            if (lower.contains("output coverage") || lower.contains("heavy contributors")) {
                return "周报作者代表产出覆盖范围提升";
            }
            if (lower.contains("author contributions") || lower.contains("branch breakdown")) {
                return "周报改为按作者归类展示代表产出";
            }
            if (lower.contains("chinese") || lower.contains("localization") || lower.contains("alias")) {
                return "周报输出中文化与作者别名归并优化";
            }
            if (lower.contains("safe.directory")) {
                return "周报仓库 safe.directory 兼容修复";
            }
            if (lower.contains("runtime image") || lower.contains("install git")) {
                return "周报运行镜像补充 Git 依赖";
            }
            if (lower.contains("origin branches") || lower.contains("repo path")) {
                return "周报按 origin 分支聚合作者提交";
            }
            if (lower.contains("summary") || lower.contains("stats")) {
                return "周报统计总览与重点模块信息增强";
            }
            return "周报生成能力优化";
        }

        if (lower.contains("gitlab") && (lower.contains("auth") || lower.contains("oauth"))) {
            return "GitLab 认证与权限能力优化";
        }

        if ((lower.contains("risk") && lower.contains("ip")) || lower.contains("approval ip")) {
            return "风险 IP 审计链路补齐";
        }

        if (lower.contains("deploy") || lower.contains("docker") || lower.contains("compose") || lower.contains("robot_env")) {
            return "部署流程与环境治理优化";
        }

        if (lower.contains("readability") || lower.contains("table") || lower.contains("sidebar") || lower.contains("layout")) {
            return "前端可读性与交互体验优化";
        }

        if (lower.contains("terminate") || lower.contains("websocket") || lower.contains("redis") || lower.contains("task")) {
            return "任务执行与终止链路优化";
        }

        if (lower.contains("approval")) {
            return "审批流程能力优化";
        }

        String singleWord = normalizedSubject.toLowerCase(Locale.ROOT);
        if (singleWord.matches("^(fix|test|wip|tmp|misc|draft|done)$")) {
            return authorThemeLabel(rawSubject);
        }

        return authorThemeLabel(rawSubject);
    }

    private String authorThemeLabel(String subject) {
        if (!StringUtils.hasText(subject)) {
            return "通用能力迭代";
        }

        String normalizedSubject = subject.toLowerCase(Locale.ROOT);
        if (normalizedSubject.contains("weekly-report") || normalizedSubject.contains("weekly report") || normalizedSubject.contains("周报")) {
            return "周报能力优化";
        }

        for (ThemeRule rule : THEME_RULES) {
            if (!rule.pattern().matcher(subject).find()) {
                continue;
            }
            return switch (rule.name()) {
                case "任务执行与终止链路" -> "任务执行与终止链路优化";
                case "审批与风控能力" -> "审批风控能力优化";
                case "GitLab 认证与权限" -> "GitLab 认证与权限优化";
                case "部署与环境治理" -> "部署与环境治理优化";
                case "前端体验与可读性" -> "前端体验与可读性优化";
                case "文档与规范" -> "文档与规范同步";
                default -> "通用能力迭代";
            };
        }
        return "通用能力迭代";
    }

    private int maxOutputsForAuthor(int commitCount) {
        if (commitCount >= 100) {
            return 8;
        }
        if (commitCount >= 70) {
            return 7;
        }
        if (commitCount >= 40) {
            return 6;
        }
        if (commitCount >= 20) {
            return 5;
        }
        return 4;
    }

    private int minOutputsForAuthor(int commitCount) {
        if (commitCount >= 100) {
            return 6;
        }
        if (commitCount >= 70) {
            return 5;
        }
        if (commitCount >= 40) {
            return 4;
        }
        if (commitCount >= 20) {
            return 3;
        }
        return 2;
    }

    private String canonicalAuthorName(String rawAuthor) {
        if (!StringUtils.hasText(rawAuthor)) {
            return "未知作者";
        }
        String author = rawAuthor.trim();
        String alias = AUTHOR_ALIAS_MAP.get(author);
        if (StringUtils.hasText(alias)) {
            return alias;
        }

        String normalized = normalizeAuthorKey(author);
        for (Map.Entry<String, String> entry : AUTHOR_ALIAS_MAP.entrySet()) {
            if (normalizeAuthorKey(entry.getKey()).equals(normalized)) {
                return entry.getValue();
            }
        }
        return author;
    }

    private String normalizeAuthorKey(String author) {
        return author.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\u4e00-\u9fa5]", "");
    }

    private boolean containsChinese(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private List<CommitRecord> listAllCommits(DateWindow window) {
        String since = formatGitTime(window.sinceInclusive());
        String until = formatGitTime(window.untilExclusive());

        List<String> lines = runGit(List.of(
                "git", "log", "--all",
                "--since=" + since,
                "--until=" + until,
                "--no-merges",
                "--pretty=format:%H%x09%an%x09%s"
        ));

        List<CommitRecord> records = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\t", 3);
            if (parts.length < 3) {
                continue;
            }
            records.add(new CommitRecord(parts[0], parts[1], parts[2]));
        }
        return records;
    }

    private int countMergeCommits(DateWindow window) {
        String since = formatGitTime(window.sinceInclusive());
        String until = formatGitTime(window.untilExclusive());

        List<String> lines = runGit(List.of(
                "git", "log", "--all",
                "--since=" + since,
                "--until=" + until,
                "--merges",
                "--pretty=format:%H"
        ));
        int count = 0;
        for (String line : lines) {
            if (StringUtils.hasText(line)) {
                count++;
            }
        }
        return count;
    }

    private DiffStats collectDiffStats(DateWindow window) {
        String since = formatGitTime(window.sinceInclusive());
        String until = formatGitTime(window.untilExclusive());

        List<String> lines = runGit(List.of(
                "git", "log", "--all",
                "--since=" + since,
                "--until=" + until,
                "--no-merges",
                "--pretty=tformat:",
                "--numstat"
        ));

        long added = 0;
        long deleted = 0;
        for (String line : lines) {
            if (!StringUtils.hasText(line)) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length < 2) {
                continue;
            }
            if (!"-".equals(parts[0])) {
                try {
                    added += Long.parseLong(parts[0]);
                } catch (NumberFormatException ignore) {
                    // ignore malformed numstat
                }
            }
            if (!"-".equals(parts[1])) {
                try {
                    deleted += Long.parseLong(parts[1]);
                } catch (NumberFormatException ignore) {
                    // ignore malformed numstat
                }
            }
        }

        return new DiffStats(added, deleted);
    }

    private List<ModuleCount> summarizeFocusModules(DateWindow window) {
        String since = formatGitTime(window.sinceInclusive());
        String until = formatGitTime(window.untilExclusive());

        List<String> lines = runGit(List.of(
                "git", "log", "--all",
                "--since=" + since,
                "--until=" + until,
                "--no-merges",
                "--name-only",
                "--pretty=format:"
        ));

        Map<String, Long> moduleCounts = new HashMap<>();
        for (String line : lines) {
            String filePath = line == null ? "" : line.trim();
            if (filePath.isEmpty()) {
                continue;
            }
            String module = toModuleName(filePath);
            moduleCounts.merge(module, 1L, Long::sum);
        }

        return moduleCounts.entrySet().stream()
                .map(e -> new ModuleCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(ModuleCount::count).reversed()
                        .thenComparing(ModuleCount::module))
                .limit(3)
                .toList();
    }

    private String toModuleName(String filePath) {
        String normalized = filePath.replace('\\', '/');
        int idx = normalized.indexOf('/');
        if (idx < 0) {
            return normalized;
        }
        String top = normalized.substring(0, idx);
        if ("robot-web-front".equals(top) || "robot-web-service".equals(top) || "docs".equals(top)) {
            return top;
        }
        return top;
    }

    private List<ThemeStat> summarizeThemes(List<CommitRecord> commits) {
        Map<String, ThemeStatBuilder> statMap = new LinkedHashMap<>();
        for (ThemeRule rule : THEME_RULES) {
            statMap.put(rule.name(), new ThemeStatBuilder(rule.name()));
        }
        ThemeStatBuilder other = new ThemeStatBuilder("综合修复与其他");

        for (CommitRecord commit : commits) {
            String subject = commit.subject();
            boolean matched = false;
            for (ThemeRule rule : THEME_RULES) {
                if (rule.pattern().matcher(subject).find()) {
                    statMap.get(rule.name()).addSample(subject);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                other.addSample(subject);
            }
        }

        List<ThemeStat> top = statMap.values().stream()
                .filter(v -> v.count > 0)
                .map(ThemeStatBuilder::build)
                .sorted(Comparator.comparingLong(ThemeStat::count).reversed())
                .limit(5)
                .collect(Collectors.toCollection(ArrayList::new));

        ThemeStat otherStat = other.build();
        if (otherStat.count() > 0 && top.size() < 5) {
            top.add(otherStat);
        }

        return top;
    }

    private List<KeywordCount> summarizeKeywords(List<CommitRecord> commits) {
        List<KeywordCount> counts = new ArrayList<>();
        for (KeywordRule rule : KEYWORD_RULES) {
            long count = commits.stream()
                    .map(CommitRecord::subject)
                    .filter(subject -> rule.pattern().matcher(subject).find())
                    .count();
            if (count > 0) {
                counts.add(new KeywordCount(rule.name(), count));
            }
        }

        return counts.stream()
                .sorted(Comparator.comparingLong(KeywordCount::count).reversed())
                .limit(8)
                .toList();
    }

    private long countActiveBranches(List<AuthorBranchStat> authorStats) {
        Set<String> branches = new LinkedHashSet<>();
        for (AuthorBranchStat author : authorStats) {
            for (BranchCount branch : author.branches()) {
                branches.add(branch.branch());
            }
        }
        return branches.size();
    }

    private String formatGitTime(LocalDateTime time) {
        return time.format(GIT_TIME_FMT);
    }

    private String normalizeBranchName(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "-";
        }
        String branch = raw.trim();
        if (branch.startsWith("origin/")) {
            return branch.substring("origin/".length());
        }
        return branch;
    }

    private List<String> runGit(List<String> command) {
        Path repoDir = resolveRepoPath();
        if (!Files.exists(repoDir.resolve(".git"))) {
            throw new IllegalStateException("未找到 Git 仓库，请配置 robot.weekly-report.repo-path");
        }

        List<String> actualCommand = withSafeDirectory(command, repoDir);
        ProcessBuilder pb = new ProcessBuilder(actualCommand);
        pb.directory(repoDir.toFile());

        try {
            Process process = pb.start();
            List<String> lines;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                lines = reader.lines().toList();
            }

            String err;
            try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                err = errReader.lines().collect(Collectors.joining("\n"));
            }

            int code = process.waitFor();
            if (code != 0) {
                throw new IllegalStateException(String.format("git 命令执行失败: %s, err=%s", String.join(" ", actualCommand), err));
            }
            return lines;
        } catch (Exception e) {
            throw new IllegalStateException("读取 git 记录失败", e);
        }
    }

    private List<String> withSafeDirectory(List<String> command, Path repoDir) {
        if (command == null || command.isEmpty()) {
            return command;
        }
        if (!"git".equals(command.get(0))) {
            return command;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-c");
        cmd.add("safe.directory=" + repoDir.toString());
        cmd.addAll(command.subList(1, command.size()));
        return cmd;
    }

    private Path resolveRepoPath() {
        if (configuredRepoPath != null && !configuredRepoPath.isBlank()) {
            Path configured = Paths.get(configuredRepoPath).toAbsolutePath().normalize();
            if (Files.exists(configured.resolve(".git"))) {
                return configured;
            }
            log.warn("robot.weekly-report.repo-path 未找到 .git: {}", configured);
        }

        Path current = Paths.get("").toAbsolutePath().normalize();
        Path cursor = current;
        while (cursor != null) {
            if (Files.exists(cursor.resolve(".git"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return current;
    }

    private record DateWindow(LocalDate startDate,
                              LocalDate endDate,
                              LocalDateTime sinceInclusive,
                              LocalDateTime untilExclusive) {
    }

    private record CommitRecord(String hash, String author, String subject) {
    }

    private record ThemeRule(String name, Pattern pattern) {
    }

    private record KeywordRule(String name, Pattern pattern) {
    }

    private record BranchCount(String branch, long count) {
    }

    private record ModuleCount(String module, long count) {
    }

    private record DiffStats(long addedLines, long deletedLines) {
    }

    private record AuthorBranchStat(String author, long totalCommits, List<BranchCount> branches) {
    }

    private record AuthorContribution(String author, int commitCount, List<String> outputs) {
    }

    private record ThemeStat(String name, long count, List<String> samples) {
    }

    private record KeywordCount(String keyword, long count) {
    }

    private record WeeklyReportData(LocalDate startDate,
                                    LocalDate endDate,
                                    int commitCount,
                                    int mergeCommitCount,
                                    long addedLines,
                                    long deletedLines,
                                    Set<String> authors,
                                    long activeBranchCount,
                                    List<ModuleCount> focusModules,
                                    List<AuthorContribution> authorContributions,
                                    List<ThemeStat> themeStats,
                                    List<KeywordCount> keywordCounts) {
    }

    private static class ThemeStatBuilder {
        private final String name;
        private long count;
        private final List<String> samples = new ArrayList<>();

        private ThemeStatBuilder(String name) {
            this.name = name;
        }

        private void addSample(String sample) {
            this.count++;
            if (samples.size() < 2) {
                samples.add(truncate(sample, 80));
            }
        }

        private ThemeStat build() {
            return new ThemeStat(name, count, samples);
        }

        private static String truncate(String value, int maxLen) {
            if (value == null) {
                return "-";
            }
            return value.length() > maxLen ? value.substring(0, maxLen) + "..." : value;
        }
    }
}
