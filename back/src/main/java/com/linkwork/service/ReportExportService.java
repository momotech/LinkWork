package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkwork.mapper.LinkworkTaskMapper;
import com.linkwork.mapper.WorkstationMapper;
import com.linkwork.model.dto.ReportExportRequest;
import com.linkwork.model.entity.LinkworkTask;
import com.linkwork.model.entity.WorkstationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportExportService {

    private final LinkworkTaskMapper taskMapper;
    private final WorkstationMapper workstationMapper;

    private static final DateTimeFormatter PARSER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss]");

    private static final Map<String, String> TASK_FIELDS = new LinkedHashMap<>() {{
        put("taskNo", "Task No");
        put("workstationName", "Role Name");
        put("prompt", "Prompt");
        put("status", "Status");
        put("selectedModel", "Model");
        put("creatorName", "Creator");
        put("tokensUsed", "Tokens Used");
        put("durationMs", "Duration(ms)");
        put("createdAt", "Created At");
    }};

    private static final Map<String, String> ROLE_FIELDS = new LinkedHashMap<>() {{
        put("name", "Name");
        put("category", "Category");
        put("status", "Status");
        put("creatorName", "Creator");
        put("createdAt", "Created At");
    }};

    public Map<String, Object> listFields(String type) {
        Map<String, String> fields = "role".equalsIgnoreCase(type) ? ROLE_FIELDS : TASK_FIELDS;
        List<Map<String, String>> fieldList = new ArrayList<>();
        fields.forEach((key, label) -> fieldList.add(Map.of("key", key, "label", label)));
        return Map.of("type", type, "fields", fieldList);
    }

    public void exportCsv(ReportExportRequest request, OutputStream outputStream) throws IOException {
        String type = request.getType();
        LocalDateTime start = LocalDateTime.parse(request.getStartTime(), PARSER);
        LocalDateTime end = LocalDateTime.parse(request.getEndTime(), PARSER);

        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        writer.write("\uFEFF");

        if ("role".equalsIgnoreCase(type)) {
            exportRoleCsv(writer, start, end, request.getFields());
        } else {
            exportTaskCsv(writer, start, end, request.getFields());
        }
        writer.flush();
    }

    private void exportTaskCsv(BufferedWriter writer, LocalDateTime start, LocalDateTime end,
                                List<String> selectedFields) throws IOException {
        Map<String, String> fields = resolveFields(TASK_FIELDS, selectedFields);
        writer.write(String.join(",", fields.values()));
        writer.newLine();

        LambdaQueryWrapper<LinkworkTask> w = new LambdaQueryWrapper<>();
        w.ge(LinkworkTask::getCreatedAt, start).le(LinkworkTask::getCreatedAt, end);
        w.orderByDesc(LinkworkTask::getCreatedAt);
        List<LinkworkTask> tasks = taskMapper.selectList(w);

        for (LinkworkTask task : tasks) {
            List<String> row = new ArrayList<>();
            for (String key : fields.keySet()) {
                row.add(csvEscape(getTaskField(task, key)));
            }
            writer.write(String.join(",", row));
            writer.newLine();
        }
    }

    private void exportRoleCsv(BufferedWriter writer, LocalDateTime start, LocalDateTime end,
                                List<String> selectedFields) throws IOException {
        Map<String, String> fields = resolveFields(ROLE_FIELDS, selectedFields);
        writer.write(String.join(",", fields.values()));
        writer.newLine();

        LambdaQueryWrapper<WorkstationEntity> w = new LambdaQueryWrapper<>();
        w.ge(WorkstationEntity::getCreatedAt, start).le(WorkstationEntity::getCreatedAt, end);
        w.orderByDesc(WorkstationEntity::getCreatedAt);
        List<WorkstationEntity> roles = workstationMapper.selectList(w);

        for (WorkstationEntity role : roles) {
            List<String> row = new ArrayList<>();
            for (String key : fields.keySet()) {
                row.add(csvEscape(getRoleField(role, key)));
            }
            writer.write(String.join(",", row));
            writer.newLine();
        }
    }

    private Map<String, String> resolveFields(Map<String, String> allFields, List<String> selected) {
        if (selected == null || selected.isEmpty()) return allFields;
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : selected) {
            String label = allFields.get(key);
            if (label != null) result.put(key, label);
        }
        return result.isEmpty() ? allFields : result;
    }

    private String getTaskField(LinkworkTask task, String key) {
        return switch (key) {
            case "taskNo" -> task.getTaskNo();
            case "workstationName" -> task.getWorkstationName();
            case "prompt" -> task.getPrompt();
            case "status" -> task.getStatus() != null ? task.getStatus().name() : "";
            case "selectedModel" -> task.getSelectedModel();
            case "creatorName" -> task.getCreatorName();
            case "tokensUsed" -> task.getTokensUsed() != null ? task.getTokensUsed().toString() : "";
            case "durationMs" -> task.getDurationMs() != null ? task.getDurationMs().toString() : "";
            case "createdAt" -> task.getCreatedAt() != null ? task.getCreatedAt().toString() : "";
            default -> "";
        };
    }

    private String getRoleField(WorkstationEntity role, String key) {
        return switch (key) {
            case "name" -> role.getName();
            case "category" -> role.getCategory();
            case "status" -> role.getStatus();
            case "creatorName" -> role.getCreatorName();
            case "createdAt" -> role.getCreatedAt() != null ? role.getCreatedAt().toString() : "";
            default -> "";
        };
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
