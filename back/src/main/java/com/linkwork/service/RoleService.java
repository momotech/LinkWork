package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linkwork.mapper.role.RoleMapper;
import com.linkwork.model.mcp.McpServerRecord;
import com.linkwork.model.role.RoleRecord;
import com.linkwork.service.mcp.McpServerService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RoleService extends ServiceImpl<RoleMapper, RoleRecord> {

    private static final Set<String> SUPPORTED_STATUSES = Set.of("ACTIVE", "MAINTENANCE", "DISABLED");
    private static final Set<String> SUPPORTED_DEPLOY_MODES = Set.of("K8S", "COMPOSE");
    private static final Set<String> SUPPORTED_RUNTIME_MODES = Set.of("ALONE", "SIDECAR");

    private final McpServerService mcpServerService;

    public RoleService(McpServerService mcpServerService) {
        this.mcpServerService = mcpServerService;
    }

    public Map<String, Object> listRoles(int page,
                                         int pageSize,
                                         String query,
                                         String category,
                                         String scope,
                                         String status,
                                         String userId) {
        Page<RoleRecord> pageObj = new Page<>(Math.max(page, 1), Math.max(pageSize, 1));
        LambdaQueryWrapper<RoleRecord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query)) {
            wrapper.and(w -> w.like(RoleRecord::getName, query)
                .or()
                .like(RoleRecord::getDescription, query));
        }
        if (StringUtils.hasText(category)) {
            wrapper.eq(RoleRecord::getCategory, category);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(RoleRecord::getStatus, normalizeDbStatus(status));
        }
        if ("favorite".equalsIgnoreCase(scope)) {
            wrapper.eq(RoleRecord::getFavorite, true);
        }
        wrapper.orderByDesc(RoleRecord::getFavorite).orderByDesc(RoleRecord::getUpdatedAt);

        Page<RoleRecord> result = this.page(pageObj, wrapper);
        List<Map<String, Object>> items = result.getRecords().stream()
            .map(record -> toRoleSummary(record, userId))
            .toList();

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", result.getCurrent());
        pagination.put("pageSize", result.getSize());
        pagination.put("total", result.getTotal());
        pagination.put("totalPages", result.getPages());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("pagination", pagination);
        return data;
    }

    public List<Map<String, Object>> listHotRoles(int limit, String userId) {
        int safeLimit = Math.max(limit, 1);
        LambdaQueryWrapper<RoleRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(RoleRecord::getFavorite).orderByDesc(RoleRecord::getUpdatedAt).last("limit " + safeLimit);
        return this.list(wrapper).stream()
            .map(record -> {
                Map<String, Object> item = toRoleSummary(record, userId);
                item.remove("resourceCount");
                return item;
            })
            .toList();
    }

    public Map<String, Object> getRoleForRead(Long id, String userId) {
        RoleRecord record = requireRole(id);
        return toRoleDetail(record, userId);
    }

    public Map<String, Object> createRole(Map<String, Object> request, String userId, String userName) {
        RoleRecord record = new RoleRecord();
        applyMutableFields(record, request, true);
        if (this.lambdaQuery().eq(RoleRecord::getName, record.getName()).count() > 0) {
            throw new IllegalArgumentException("岗位名称已存在: " + record.getName());
        }
        this.save(record);
        return toRoleDetail(record, userId);
    }

    public Map<String, Object> updateRole(Long id, Map<String, Object> request, String userId) {
        RoleRecord record = requireRole(id);
        String oldName = record.getName();
        applyMutableFields(record, request, false);
        if (StringUtils.hasText(record.getName()) && !record.getName().equals(oldName)) {
            if (this.lambdaQuery().eq(RoleRecord::getName, record.getName()).ne(RoleRecord::getId, id).count() > 0) {
                throw new IllegalArgumentException("岗位名称已存在: " + record.getName());
            }
        }
        this.updateById(record);
        RoleRecord fresh = requireRole(id);
        return toRoleDetail(fresh, userId);
    }

    public void deleteRole(Long id) {
        requireRole(id);
        this.removeById(id);
    }

    public void toggleFavorite(Long id, boolean favorite) {
        RoleRecord record = requireRole(id);
        record.setFavorite(favorite);
        this.updateById(record);
    }

    private RoleRecord requireRole(Long id) {
        RoleRecord record = this.getById(id);
        if (record == null) {
            throw new IllegalArgumentException("岗位不存在: " + id);
        }
        return record;
    }

    private void applyMutableFields(RoleRecord record, Map<String, Object> request, boolean creating) {
        String name = asString(request.get("name"));
        if (creating) {
            if (!StringUtils.hasText(name)) {
                throw new IllegalArgumentException("岗位名称不能为空");
            }
            record.setName(name.trim());
        } else if (StringUtils.hasText(name)) {
            record.setName(name.trim());
        }

        if (request.containsKey("description")) {
            record.setDescription(asString(request.get("description")));
        } else if (creating && record.getDescription() == null) {
            record.setDescription("");
        }

        if (request.containsKey("category")) {
            record.setCategory(defaultIfBlank(asString(request.get("category")), "default"));
        } else if (creating && !StringUtils.hasText(record.getCategory())) {
            record.setCategory("default");
        }

        if (request.containsKey("icon")) {
            record.setIcon(defaultIfBlank(asString(request.get("icon")), "bot"));
        } else if (creating && !StringUtils.hasText(record.getIcon())) {
            record.setIcon("bot");
        }

        if (request.containsKey("image")) {
            record.setImage(asString(request.get("image")));
        }

        if (request.containsKey("prompt")) {
            String prompt = asString(request.get("prompt"));
            if (!StringUtils.hasText(prompt)) {
                throw new IllegalArgumentException("岗位 Prompt 不能为空");
            }
            record.setPrompt(prompt);
        } else if (creating && !StringUtils.hasText(record.getPrompt())) {
            throw new IllegalArgumentException("岗位 Prompt 不能为空");
        }

        if (request.containsKey("isPublic")) {
            record.setIsPublic(asBoolean(request.get("isPublic"), false));
        } else if (creating && record.getIsPublic() == null) {
            record.setIsPublic(false);
        }

        if (request.containsKey("maxEmployees")) {
            record.setMaxEmployees(Math.max(asInt(request.get("maxEmployees"), 1), 1));
        } else if (creating && record.getMaxEmployees() == null) {
            record.setMaxEmployees(1);
        }

        if (request.containsKey("status")) {
            record.setStatus(normalizeDbStatus(asString(request.get("status"))));
        } else if (creating && !StringUtils.hasText(record.getStatus())) {
            record.setStatus("ACTIVE");
        }

        if (request.containsKey("configJson")) {
            Map<String, Object> config = asMap(request.get("configJson"));
            normalizeConfig(config);
            record.setConfigJson(config);
        } else if (creating && (record.getConfigJson() == null || record.getConfigJson().isEmpty())) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("deployMode", "K8S");
            config.put("runtimeMode", "ALONE");
            record.setConfigJson(config);
        }

        if (creating && record.getFavorite() == null) {
            record.setFavorite(false);
        }
    }

    private void normalizeConfig(Map<String, Object> config) {
        if (config == null) {
            return;
        }
        String deployMode = normalizeDeployMode(asString(config.get("deployMode")));
        String runtimeMode = normalizeRuntimeMode(asString(config.get("runtimeMode")));
        if ("COMPOSE".equals(deployMode)) {
            runtimeMode = "ALONE";
        }
        config.put("deployMode", deployMode);
        config.put("runtimeMode", runtimeMode);
        if (!"SIDECAR".equals(runtimeMode)) {
            config.put("runnerImage", null);
        }
        config.put("mcp", toRefList(config.get("mcp")));
        config.put("skills", toRefList(config.get("skills")));
        config.put("gitRepos", normalizeGitRepos(config.get("gitRepos")));
        config.put("env", normalizeEnv(config.get("env")));
        config.put("memoryEnabled", asBoolean(config.get("memoryEnabled"), false));
    }

    private Map<String, Object> toRoleSummary(RoleRecord record, String userId) {
        Map<String, Object> config = record.getConfigJson() == null ? Map.of() : record.getConfigJson();
        String deployMode = normalizeDeployMode(asString(config.get("deployMode")));
        String runtimeMode = normalizeRuntimeMode(asString(config.get("runtimeMode")));
        String runnerImage = asString(config.get("runnerImage"));
        if (!"SIDECAR".equals(runtimeMode)) {
            runnerImage = null;
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", String.valueOf(record.getId()));
        item.put("name", record.getName());
        item.put("description", record.getDescription());
        item.put("category", defaultIfBlank(record.getCategory(), "default"));
        item.put("icon", defaultIfBlank(record.getIcon(), "bot"));
        item.put("image", record.getImage());
        item.put("status", toApiStatus(record.getStatus()));
        item.put("deployMode", deployMode);
        item.put("runtimeMode", runtimeMode);
        item.put("zzMode", runtimeMode);
        item.put("runnerImage", runnerImage);
        item.put("memoryEnabled", asBoolean(config.get("memoryEnabled"), false));
        item.put("isMine", true);
        item.put("isFavorite", Boolean.TRUE.equals(record.getFavorite()));
        item.put("favoriteCount", Boolean.TRUE.equals(record.getFavorite()) ? 1L : 0L);
        item.put("isPublic", Boolean.TRUE.equals(record.getIsPublic()));
        item.put("maxEmployees", record.getMaxEmployees() == null ? 1 : record.getMaxEmployees());

        Map<String, Object> resourceCount = new LinkedHashMap<>();
        resourceCount.put("mcp", toRefList(config.get("mcp")).size());
        resourceCount.put("skills", toRefList(config.get("skills")).size());
        item.put("resourceCount", resourceCount);
        return item;
    }

    private Map<String, Object> toRoleDetail(RoleRecord record, String userId) {
        Map<String, Object> item = toRoleSummary(record, userId);
        Map<String, Object> config = record.getConfigJson() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(record.getConfigJson());
        item.put("prompt", record.getPrompt());
        item.put("createdAt", record.getCreatedAt());
        List<Map<String, Object>> mcpModules = resolveMcpModules(toRefList(config.get("mcp")));
        item.put("mcpModules", mcpModules);
        item.put("mcpServers", mcpModules);
        item.put("skills", resolveSkillModules(toRefList(config.get("skills"))));
        item.put("gitRepos", normalizeGitRepos(config.get("gitRepos")));
        item.put("envVars", normalizeEnv(config.get("env")));
        return item;
    }

    private List<Map<String, Object>> resolveMcpModules(List<String> refs) {
        if (refs.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> modules = new ArrayList<>();
        for (String ref : refs) {
            if (!StringUtils.hasText(ref)) {
                continue;
            }
            McpServerRecord record = findMcpServer(ref.trim());
            if (record == null) {
                modules.add(module(ref, ref, "MCP 配置不存在"));
                continue;
            }
            modules.add(module(String.valueOf(record.getId()), record.getName(), record.getDescription()));
        }
        return modules;
    }

    private McpServerRecord findMcpServer(String ref) {
        try {
            Long id = Long.parseLong(ref);
            return mcpServerService.getById(id);
        } catch (NumberFormatException ignored) {
            LambdaQueryWrapper<McpServerRecord> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(McpServerRecord::getName, ref).last("limit 1");
            return mcpServerService.getOne(wrapper, false);
        }
    }

    private List<Map<String, Object>> resolveSkillModules(List<String> refs) {
        if (refs.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> modules = new ArrayList<>();
        for (String ref : refs) {
            modules.add(module(ref, ref, "Skill"));
        }
        return modules;
    }

    private Map<String, Object> module(String id, String name, String description) {
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("id", id);
        module.put("name", name);
        module.put("description", description);
        return module;
    }

    private String normalizeDbStatus(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "ACTIVE";
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("非法岗位状态: " + raw);
        }
        return normalized;
    }

    private String toApiStatus(String dbStatus) {
        if (!StringUtils.hasText(dbStatus)) {
            return "active";
        }
        String normalized = dbStatus.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_STATUSES.contains(normalized)) {
            return "active";
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeDeployMode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "K8S";
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_DEPLOY_MODES.contains(normalized)) {
            throw new IllegalArgumentException("非法部署模式: " + raw);
        }
        return normalized;
    }

    private String normalizeRuntimeMode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "ALONE";
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_RUNTIME_MODES.contains(normalized)) {
            throw new IllegalArgumentException("非法运行模式: " + raw);
        }
        return normalized;
    }

    private List<String> toRefList(Object raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        List<String> refs = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                if (item instanceof Map<?, ?> map) {
                    String id = asString(map.get("id"));
                    String name = asString(map.get("name"));
                    if (StringUtils.hasText(id)) {
                        refs.add(id.trim());
                    } else if (StringUtils.hasText(name)) {
                        refs.add(name.trim());
                    }
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (StringUtils.hasText(text)) {
                    refs.add(text);
                }
            }
            return refs;
        }
        String text = String.valueOf(raw).trim();
        if (StringUtils.hasText(text)) {
            refs.add(text);
        }
        return refs;
    }

    private List<Map<String, Object>> normalizeGitRepos(Object raw) {
        List<Map<String, Object>> repos = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return repos;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                String url = asString(map.get("url"));
                if (!StringUtils.hasText(url)) {
                    continue;
                }
                normalized.put("url", url);
                String branch = asString(map.get("branch"));
                if (StringUtils.hasText(branch)) {
                    normalized.put("branch", branch);
                }
                repos.add(normalized);
            } else if (item instanceof String str && StringUtils.hasText(str)) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("url", str.trim());
                repos.add(normalized);
            }
        }
        return repos;
    }

    private List<Map<String, String>> normalizeEnv(Object raw) {
        List<Map<String, String>> envVars = new ArrayList<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = asString(entry.getKey());
                if (!StringUtils.hasText(key)) {
                    continue;
                }
                Map<String, String> item = new LinkedHashMap<>();
                item.put("key", key.trim());
                item.put("value", entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                envVars.add(item);
            }
            return envVars;
        }
        if (!(raw instanceof List<?> list)) {
            return envVars;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String key = asString(map.get("key"));
            if (!StringUtils.hasText(key)) {
                continue;
            }
            String value = asString(map.get("value"));
            Map<String, String> normalized = new LinkedHashMap<>();
            normalized.put("key", key.trim());
            normalized.put("value", value == null ? "" : value);
            envVars.add(normalized);
        }
        return envVars;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text) || "y".equals(text) || "public".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text) || "n".equals(text) || "private".equals(text)) {
            return false;
        }
        return defaultValue;
    }

    private int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
