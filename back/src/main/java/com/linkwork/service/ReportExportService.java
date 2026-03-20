package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkwork.config.DispatchConfig;
import com.linkwork.mapper.RoleMapper;
import com.linkwork.mapper.TaskMapper;
import com.linkwork.model.dto.ReportExportFieldOption;
import com.linkwork.model.dto.ReportExportFieldResponse;
import com.linkwork.model.dto.ReportExportRequest;
import com.linkwork.model.entity.RoleEntity;
import com.linkwork.model.entity.Task;
import com.linkwork.model.enums.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运维报表导出服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportExportService {

    private static final int PAGE_SIZE = 500;
    private static final String TYPE_TASK = "task";
    private static final String TYPE_ROLE = "role";
    private static final String FIELD_EVENT_STREAM = "eventStream";
    private static final DateTimeFormatter DATE_TIME_OUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final List<DateTimeFormatter> LOCAL_DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    );

    private static final Map<String, String> TASK_FIELD_LABELS = Map.ofEntries(
            Map.entry("id", "主键ID"),
            Map.entry("taskNo", "任务编号"),
            Map.entry("roleId", "岗位ID"),
            Map.entry("roleName", "岗位名称"),
            Map.entry("prompt", "任务目标"),
            Map.entry("status", "任务状态"),
            Map.entry("image", "镜像"),
            Map.entry("selectedModel", "模型"),
            Map.entry("assemblyId", "装配ID"),
            Map.entry("configJson", "任务配置"),
            Map.entry("source", "任务来源"),
            Map.entry("cronJobId", "定时任务ID"),
            Map.entry("creatorId", "创建人ID"),
            Map.entry("creatorName", "创建人"),
            Map.entry("creatorIp", "创建IP"),
            Map.entry("updaterId", "更新人ID"),
            Map.entry("updaterName", "更新人"),
            Map.entry("tokensUsed", "总Tokens"),
            Map.entry("inputTokens", "输入Tokens"),
            Map.entry("outputTokens", "输出Tokens"),
            Map.entry("requestCount", "请求次数"),
            Map.entry("tokenLimit", "Token上限"),
            Map.entry("usagePercent", "使用率"),
            Map.entry("durationMs", "执行耗时(ms)"),
            Map.entry("reportJson", "任务报告"),
            Map.entry("createdAt", "创建时间"),
            Map.entry("updatedAt", "更新时间"),
            Map.entry("isDeleted", "逻辑删除标记")
    );

    private static final Map<String, String> ROLE_FIELD_LABELS = Map.ofEntries(
            Map.entry("id", "主键ID"),
            Map.entry("roleNo", "岗位编号"),
            Map.entry("name", "岗位名称"),
            Map.entry("description", "岗位描述"),
            Map.entry("category", "岗位分类"),
            Map.entry("icon", "图标"),
            Map.entry("image", "镜像"),
            Map.entry("prompt", "岗位Prompt"),
            Map.entry("status", "岗位状态"),
            Map.entry("configJson", "岗位配置"),
            Map.entry("isPublic", "是否公开"),
            Map.entry("maxEmployees", "最大员工数"),
            Map.entry("creatorId", "创建人ID"),
            Map.entry("creatorName", "创建人"),
            Map.entry("updaterId", "更新人ID"),
            Map.entry("updaterName", "更新人"),
            Map.entry("createdAt", "创建时间"),
            Map.entry("updatedAt", "更新时间"),
            Map.entry("isDeleted", "逻辑删除标记")
    );

    private final TaskMapper taskMapper;
    private final RoleMapper roleMapper;
    private final StringRedisTemplate redisTemplate;
    private final DispatchConfig dispatchConfig;
    private final ObjectMapper objectMapper;
    private final Map<Class<?>, Map<String, Field>> fieldCache = new ConcurrentHashMap<>();

    public ReportExportFieldResponse listFields(String type) {
        String normalizedType = normalizeType(type);
        List<ReportExportFieldOption> fields = TYPE_TASK.equals(normalizedType)
                ? buildFieldOptions(Task.class, TASK_FIELD_LABELS)
                : buildFieldOptions(RoleEntity.class, ROLE_FIELD_LABELS);
        return new ReportExportFieldResponse(normalizedType, fields);
    }

    public void exportCsv(ReportExportRequest request, OutputStream outputStream) throws IOException {
        String normalizedType = normalizeType(request.getType());
        LocalDateTime startTime = parseDateTime(request.getStartTime(), "开始时间");
        LocalDateTime endTime = parseDateTime(request.getEndTime(), "结束时间");
        if (endTime.isBefore(startTime)) {
            throw new IllegalArgumentException("结束时间必须大于或等于开始时间");
        }

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            // Excel 友好：UTF-8 BOM
            writer.write('\uFEFF');

            if (TYPE_TASK.equals(normalizedType)) {
                exportTaskCsv(request, startTime, endTime, writer);
            } else {
                exportRoleCsv(request, startTime, endTime, writer);
            }
            writer.flush();
        }
    }

    private void exportTaskCsv(ReportExportRequest request,
                               LocalDateTime startTime,
                               LocalDateTime endTime,
                               BufferedWriter writer) throws IOException {
        List<ReportExportFieldOption> taskFields = buildFieldOptions(Task.class, TASK_FIELD_LABELS);
        List<ReportExportFieldOption> selectedFields = resolveSelectedFields(request.getFields(), taskFields);
        boolean includeEventStream = Boolean.TRUE.equals(request.getIncludeEventStream());
        if (includeEventStream) {
            selectedFields = new ArrayList<>(selectedFields);
            selectedFields.add(new ReportExportFieldOption(FIELD_EVENT_STREAM, "event_stream", "消息流", "String"));
        }

        writeCsvRow(writer, selectedFields.stream().map(ReportExportFieldOption::getLabel).toList());

        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<Task>()
                .ge(Task::getCreatedAt, startTime)
                .le(Task::getCreatedAt, endTime)
                .orderByAsc(Task::getCreatedAt)
                .orderByAsc(Task::getId);

        long pageNo = 1L;
        while (true) {
            Page<Task> page = taskMapper.selectPage(new Page<>(pageNo, PAGE_SIZE), wrapper);
            for (Task task : page.getRecords()) {
                List<String> row = new ArrayList<>(selectedFields.size());
                for (ReportExportFieldOption option : selectedFields) {
                    if (FIELD_EVENT_STREAM.equals(option.getField())) {
                        row.add(loadTaskEventStream(task));
                    } else {
                        row.add(toCellValue(readField(task, option.getField())));
                    }
                }
                writeCsvRow(writer, row);
            }
            if (pageNo >= page.getPages()) {
                break;
            }
            pageNo++;
        }
    }

    private void exportRoleCsv(ReportExportRequest request,
                               LocalDateTime startTime,
                               LocalDateTime endTime,
                               BufferedWriter writer) throws IOException {
        List<ReportExportFieldOption> roleFields = buildFieldOptions(RoleEntity.class, ROLE_FIELD_LABELS);
        List<ReportExportFieldOption> selectedFields = resolveSelectedFields(request.getFields(), roleFields);

        writeCsvRow(writer, selectedFields.stream().map(ReportExportFieldOption::getLabel).toList());

        LambdaQueryWrapper<RoleEntity> wrapper = new LambdaQueryWrapper<RoleEntity>()
                .ge(RoleEntity::getCreatedAt, startTime)
                .le(RoleEntity::getCreatedAt, endTime)
                .orderByAsc(RoleEntity::getCreatedAt)
                .orderByAsc(RoleEntity::getId);

        long pageNo = 1L;
        while (true) {
            Page<RoleEntity> page = roleMapper.selectPage(new Page<>(pageNo, PAGE_SIZE), wrapper);
            for (RoleEntity role : page.getRecords()) {
                List<String> row = new ArrayList<>(selectedFields.size());
                for (ReportExportFieldOption option : selectedFields) {
                    row.add(toCellValue(readField(role, option.getField())));
                }
                writeCsvRow(writer, row);
            }
            if (pageNo >= page.getPages()) {
                break;
            }
            pageNo++;
        }
    }

    private List<ReportExportFieldOption> buildFieldOptions(Class<?> entityClass, Map<String, String> labelMap) {
        List<ReportExportFieldOption> fields = new ArrayList<>();
        for (Field field : entityClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String fieldName = field.getName();
            fields.add(new ReportExportFieldOption(
                    fieldName,
                    toSnakeCase(fieldName),
                    labelMap.getOrDefault(fieldName, fieldName),
                    field.getType().getSimpleName()
            ));
        }
        return fields;
    }

    private List<ReportExportFieldOption> resolveSelectedFields(List<String> fields, List<ReportExportFieldOption> allowedFields) {
        if (fields == null || fields.isEmpty()) {
            return new ArrayList<>(allowedFields);
        }

        Map<String, ReportExportFieldOption> allowed = new LinkedHashMap<>();
        for (ReportExportFieldOption option : allowedFields) {
            allowed.put(option.getField(), option);
        }

        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        for (String field : fields) {
            if (!StringUtils.hasText(field)) {
                continue;
            }
            String normalizedField = field.trim();
            if (!allowed.containsKey(normalizedField)) {
                throw new IllegalArgumentException("存在不支持的导出字段: " + normalizedField);
            }
            deduplicated.add(normalizedField);
        }

        if (deduplicated.isEmpty()) {
            throw new IllegalArgumentException("导出字段不能为空");
        }

        List<ReportExportFieldOption> selected = new ArrayList<>(deduplicated.size());
        for (String field : deduplicated) {
            selected.add(allowed.get(field));
        }
        return selected;
    }

    private String loadTaskEventStream(Task task) {
        if (!StringUtils.hasText(task.getTaskNo())) {
            return "";
        }

        List<String> streamKeys = Arrays.asList(
                dispatchConfig.getLogStreamKey(task.getRoleId(), task.getTaskNo()),
                "stream:task:" + task.getTaskNo() + ":events",
                "stream:task:" + task.getTaskNo()
        );

        for (String streamKey : streamKeys) {
            List<MapRecord<String, Object, Object>> records;
            try {
                records = redisTemplate.opsForStream().read(StreamOffset.fromStart(streamKey));
            } catch (Exception e) {
                log.warn("读取消息流失败: taskNo={}, key={}, error={}", task.getTaskNo(), streamKey, e.getMessage());
                continue;
            }
            if (records == null || records.isEmpty()) {
                continue;
            }

            List<String> lines = new ArrayList<>(records.size());
            for (MapRecord<String, Object, Object> record : records) {
                lines.add(serializeStreamRecord(streamKey, record));
            }
            return String.join("\n", lines);
        }

        return "";
    }

    private String serializeStreamRecord(String streamKey, MapRecord<String, Object, Object> record) {
        Map<String, Object> valueMap = new LinkedHashMap<>();
        valueMap.put("streamKey", streamKey);
        valueMap.put("recordId", record.getId().getValue());

        record.getValue().forEach((k, v) -> valueMap.put(String.valueOf(k), normalizeStreamValue(v)));

        try {
            return objectMapper.writeValueAsString(valueMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化消息流记录失败: streamKey=" + streamKey, e);
        }
    }

    private Object normalizeStreamValue(Object value) {
        if (!(value instanceof String text)) {
            return value;
        }
        String trimmed = text.trim();
        if (!StringUtils.hasText(trimmed)) {
            return text;
        }
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                return objectMapper.readValue(trimmed, Object.class);
            } catch (Exception e) {
                log.debug("消息流 JSON 解析失败，保留原文: {}", e.getMessage());
            }
        }
        return text;
    }

    private Object readField(Object entity, String fieldName) {
        Map<String, Field> classFields = fieldCache.computeIfAbsent(entity.getClass(), this::buildFieldMap);
        Field field = classFields.get(fieldName);
        if (field == null) {
            throw new IllegalArgumentException("未知字段: " + fieldName);
        }
        try {
            return field.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读取字段失败: " + fieldName, e);
        }
    }

    private Map<String, Field> buildFieldMap(Class<?> clazz) {
        Map<String, Field> map = new LinkedHashMap<>();
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            map.put(field.getName(), field);
        }
        return map;
    }

    private String toCellValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof TaskStatus taskStatus) {
            return taskStatus.getCode();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.format(DATE_TIME_OUT);
        }
        if (value instanceof Instant instant) {
            return DATE_TIME_OUT.format(instant.atZone(ZoneId.systemDefault()).toLocalDateTime());
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return DATE_TIME_OUT.format(offsetDateTime.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean || value instanceof BigDecimal) {
            return String.valueOf(value);
        }
        if (value.getClass().isEnum()) {
            return ((Enum<?>) value).name();
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化字段值失败: " + value.getClass().getSimpleName(), e);
        }
    }

    private void writeCsvRow(BufferedWriter writer, List<String> columns) throws IOException {
        for (int i = 0; i < columns.size(); i++) {
            writer.write(escapeCsv(columns.get(i)));
            if (i < columns.size() - 1) {
                writer.write(',');
            }
        }
        writer.write('\n');
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        if (!needQuotes) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private LocalDateTime parseDateTime(String value, String fieldName) {
        String text = value == null ? "" : value.trim();
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        for (DateTimeFormatter formatter : LOCAL_DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // 尝试下一个格式
            }
        }

        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 尝试下一个格式
        }

        try {
            return Instant.parse(text).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            throw new IllegalArgumentException(fieldName + "格式不正确: " + text);
        }
    }

    private String normalizeType(String type) {
        if (!StringUtils.hasText(type)) {
            throw new IllegalArgumentException("导出类型不能为空");
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        if (!TYPE_TASK.equals(normalized) && !TYPE_ROLE.equals(normalized)) {
            throw new IllegalArgumentException("不支持的导出类型: " + type);
        }
        return normalized;
    }

    private String toSnakeCase(String camelCase) {
        if (!StringUtils.hasText(camelCase)) {
            return camelCase;
        }
        StringBuilder builder = new StringBuilder(camelCase.length() + 8);
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    builder.append('_');
                }
                builder.append(Character.toLowerCase(c));
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
