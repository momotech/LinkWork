package com.linkwork.model.enums;

import lombok.Getter;

import java.util.Locale;
import java.util.Optional;

/**
 * 任务产出预估类型枚举
 */
@Getter
public enum TaskOutputType {
    GIT_BRANCH("git_branch", "code_delivery", "代码改动预计会落到 Git 分支"),
    PULL_REQUEST("pull_request", "code_delivery", "预计会产出 PR/MR 链接"),
    PYTHON_FILE("python_file", "code_delivery", "预计产出 Python 源码文件"),
    JAVA_FILE("java_file", "code_delivery", "预计产出 Java 源码文件"),
    JAVASCRIPT_FILE("javascript_file", "code_delivery", "预计产出 JavaScript 源码文件"),
    TYPESCRIPT_FILE("typescript_file", "code_delivery", "预计产出 TypeScript 源码文件"),
    SQL_FILE("sql_file", "code_delivery", "预计产出 SQL 脚本"),
    SHELL_SCRIPT("shell_script", "code_delivery", "预计产出 Shell 脚本"),
    CONFIG_FILE("config_file", "code_delivery", "预计产出配置文件"),

    TXT("txt", "document_delivery", "预计产出纯文本文件"),
    MARKDOWN("markdown", "document_delivery", "预计产出 Markdown 文档"),
    WORD("word", "document_delivery", "预计产出 Word 文档"),
    EXCEL("excel", "document_delivery", "预计产出 Excel 表格"),
    PPT("ppt", "document_delivery", "预计产出 PPT 文档"),
    PDF("pdf", "document_delivery", "预计产出 PDF 文档"),

    JSON("json", "data_media_delivery", "预计产出 JSON 数据文件"),
    CSV("csv", "data_media_delivery", "预计产出 CSV 数据文件"),
    PNG("png", "data_media_delivery", "预计产出图片文件"),

    ARCHIVE("archive", "package_delivery", "预计产出压缩包"),
    API_CALL_RESULT("api_call_result", "integration_delivery", "仅通过 API 调用产生结果"),
    DIALOG_CONCLUSION("dialog_conclusion", "dialog_delivery", "无文件产出，仅对话结论");

    private final String code;
    private final String domain;
    private final String description;

    TaskOutputType(String code, String domain, String description) {
        this.code = code;
        this.domain = domain;
        this.description = description;
    }

    public static Optional<TaskOutputType> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        for (TaskOutputType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    public static Optional<TaskOutputType> fromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }

        String lower = fileName.toLowerCase(Locale.ROOT);

        if (hasAnySuffix(lower, ".zip", ".tar", ".tar.gz", ".tgz", ".7z", ".rar")) {
            return Optional.of(ARCHIVE);
        }

        if (hasAnySuffix(lower, ".xlsx", ".xls")) {
            return Optional.of(EXCEL);
        }
        if (hasAnySuffix(lower, ".csv")) {
            return Optional.of(CSV);
        }
        if (hasAnySuffix(lower, ".docx", ".doc")) {
            return Optional.of(WORD);
        }
        if (hasAnySuffix(lower, ".ppt", ".pptx")) {
            return Optional.of(PPT);
        }
        if (hasAnySuffix(lower, ".pdf")) {
            return Optional.of(PDF);
        }
        if (hasAnySuffix(lower, ".md", ".markdown")) {
            return Optional.of(MARKDOWN);
        }
        if (hasAnySuffix(lower, ".txt", ".log")) {
            return Optional.of(TXT);
        }

        if (hasAnySuffix(lower, ".py")) {
            return Optional.of(PYTHON_FILE);
        }
        if (hasAnySuffix(lower, ".java")) {
            return Optional.of(JAVA_FILE);
        }
        if (hasAnySuffix(lower, ".ts", ".tsx")) {
            return Optional.of(TYPESCRIPT_FILE);
        }
        if (hasAnySuffix(lower, ".js", ".jsx", ".mjs", ".cjs")) {
            return Optional.of(JAVASCRIPT_FILE);
        }
        if (hasAnySuffix(lower, ".sql")) {
            return Optional.of(SQL_FILE);
        }
        if (hasAnySuffix(lower, ".sh", ".bash", ".zsh")) {
            return Optional.of(SHELL_SCRIPT);
        }

        if (hasAnySuffix(lower, ".yaml", ".yml", ".toml", ".ini", ".properties", ".env", ".conf", ".xml")) {
            return Optional.of(CONFIG_FILE);
        }
        if (hasAnySuffix(lower, ".json")) {
            return Optional.of(JSON);
        }
        if (hasAnySuffix(lower, ".png", ".jpg", ".jpeg", ".svg", ".webp")) {
            return Optional.of(PNG);
        }

        return Optional.empty();
    }

    private static boolean hasAnySuffix(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
