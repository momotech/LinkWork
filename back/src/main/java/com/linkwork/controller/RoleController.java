package com.linkwork.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkwork.context.UserContext;
import com.linkwork.model.entity.McpServerEntity;
import com.linkwork.model.entity.RoleEntity;
import com.linkwork.model.entity.SkillEntity;
import com.linkwork.model.enums.DeployMode;
import com.linkwork.service.K8sOrchestrator;
import com.linkwork.service.McpServerService;
import com.linkwork.service.RoleService;
import com.linkwork.service.RuntimeModeService;
import com.linkwork.service.ServiceSnapshotService;
import com.linkwork.service.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/roles")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class RoleController {
    private static final java.util.Set<String> SUPPORTED_ROLE_STATUSES = java.util.Set.of("active", "maintenance", "disabled");

    private final RoleService roleService;
    private final RuntimeModeService runtimeModeService;
    private final McpServerService mcpServerService;
    private final SkillService skillService;
    private final K8sOrchestrator k8sOrchestrator;
    private final ServiceSnapshotService snapshotService;

    private String getCurrentUserId() {
        return UserContext.getCurrentUserId();
    }

    private String getCurrentUsername() {
        return UserContext.getCurrentUserName();
    }

    @GetMapping
    public Map<String, Object> listRoles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "all") String scope
    ) {
        String userId = getCurrentUserId();
        Page<RoleEntity> result = roleService.listRoles(page, pageSize, query, category, scope, status, userId);
        Map<Long, Long> favoriteCountMap = roleService.queryFavoriteCountMap(
                result.getRecords().stream().map(RoleEntity::getId).collect(Collectors.toList())
        );

        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "success");

        Map<String, Object> data = new HashMap<>();
        List<Map<String, Object>> items = result.getRecords().stream().map(role -> {
            RuntimeModeService.RuntimeSnapshot runtimeSnapshot = runtimeModeService.resolveForRole(role);

            Map<String, Object> map = new HashMap<>();
            map.put("id", role.getId().toString());
            map.put("name", role.getName());
            map.put("description", role.getDescription());
            map.put("category", role.getCategory());
            map.put("icon", role.getIcon());
            map.put("image", role.getImage());
            map.put("status", coerceRoleStatus(role.getStatus()));
            map.put("deployMode", resolveDeployMode(role));
            map.put("runtimeMode", runtimeSnapshot.getRuntimeMode());
            map.put("zzMode", runtimeSnapshot.getZzMode());
            map.put("runnerImage", runtimeSnapshot.getRunnerImage());
            map.put("memoryEnabled", resolveMemoryEnabled(role));
            map.put("isMine", userId.equals(role.getCreatorId()));
            map.put("isFavorite", roleService.isFavorite(role.getId(), userId));
            map.put("favoriteCount", favoriteCountMap.getOrDefault(role.getId(), 0L));
            map.put("isPublic", Boolean.TRUE.equals(role.getIsPublic()));
            map.put("maxEmployees", role.getMaxEmployees());

            Map<String, Integer> resourceCount = new HashMap<>();
            if (role.getConfigJson() != null) {
                resourceCount.put("mcp", role.getConfigJson().getMcp() != null ? role.getConfigJson().getMcp().size() : 0);
                resourceCount.put("skills", role.getConfigJson().getSkills() != null ? role.getConfigJson().getSkills().size() : 0);
            }
            map.put("resourceCount", resourceCount);

            return map;
        }).collect(Collectors.toList());

        data.put("items", items);

        Map<String, Object> pagination = new HashMap<>();
        pagination.put("page", result.getCurrent());
        pagination.put("pageSize", result.getSize());
        pagination.put("total", result.getTotal());
        pagination.put("totalPages", result.getPages());
        data.put("pagination", pagination);

        response.put("data", data);
        response.put("timestamp", LocalDateTime.now().toString());

        return response;
    }

    @GetMapping("/hot")
    public Map<String, Object> listHotRoles(@RequestParam(defaultValue = "4") int limit) {
        String userId = getCurrentUserId();
        List<RoleEntity> roles = roleService.listHotRoles(limit, userId);
        Map<Long, Long> favoriteCountMap = roleService.queryFavoriteCountMap(
                roles.stream().map(RoleEntity::getId).collect(Collectors.toList())
        );

        List<Map<String, Object>> items = roles.stream().map(role -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", role.getId().toString());
            map.put("name", role.getName());
            map.put("description", role.getDescription());
            map.put("category", role.getCategory());
            map.put("status", coerceRoleStatus(role.getStatus()));
            map.put("favoriteCount", favoriteCountMap.getOrDefault(role.getId(), 0L));
            map.put("isFavorite", roleService.isFavorite(role.getId(), userId));
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "success");
        response.put("data", Map.of("items", items, "limit", Math.max(1, limit)));
        response.put("timestamp", LocalDateTime.now().toString());
        return response;
    }

    @GetMapping("/{id:\\d+}")
    public Map<String, Object> getRole(@PathVariable Long id) {
        String userId = getCurrentUserId();
        RoleEntity role = roleService.getRoleForRead(id, userId);
        RuntimeModeService.RuntimeSnapshot runtimeSnapshot = runtimeModeService.resolveForRole(role);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "success");

        Map<String, Object> data = new HashMap<>();
        data.put("id", role.getId().toString());
        data.put("name", role.getName());
        data.put("description", role.getDescription());
        data.put("prompt", role.getPrompt());
        data.put("category", role.getCategory());
        data.put("icon", role.getIcon());
        data.put("image", role.getImage());
        data.put("status", coerceRoleStatus(role.getStatus()));
        data.put("deployMode", resolveDeployMode(role));
        data.put("runtimeMode", runtimeSnapshot.getRuntimeMode());
        data.put("zzMode", runtimeSnapshot.getZzMode());
        data.put("runnerImage", runtimeSnapshot.getRunnerImage());
        data.put("memoryEnabled", resolveMemoryEnabled(role));
        data.put("isMine", userId.equals(role.getCreatorId()));
        data.put("isFavorite", roleService.isFavorite(id, userId));
        data.put("isPublic", Boolean.TRUE.equals(role.getIsPublic()));
        data.put("maxEmployees", role.getMaxEmployees());
        data.put("createdAt", role.getCreatedAt() != null ? role.getCreatedAt().toString() : null);

        if (role.getConfigJson() != null) {
            RoleEntity.RoleConfig config = role.getConfigJson();
            List<Map<String, Object>> mcpModules = resolveMcpModules(config.getMcp());
            data.put("mcpModules", mcpModules);
            data.put("mcpServers", mcpModules); // 兼容历史前端字段
            data.put("skills", resolveSkillModules(config.getSkills()));

            data.put("gitRepos", config.getGitRepos());
            data.put("envVars", config.getEnv());
        }

        response.put("data", data);
        return response;
    }

    private String resolveDeployMode(RoleEntity role) {
        if (role == null || role.getConfigJson() == null) {
            return DeployMode.K8S.name();
        }

        String rawDeployMode = role.getConfigJson().getDeployMode();
        if (!StringUtils.hasText(rawDeployMode)) {
            return DeployMode.K8S.name();
        }

        try {
            return DeployMode.valueOf(rawDeployMode.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("非法部署模式: " + rawDeployMode + ", roleId=" + role.getId());
        }
    }

    private String coerceRoleStatus(String rawStatus) {
        if (!StringUtils.hasText(rawStatus)) {
            return "active";
        }
        String normalized = rawStatus.trim().toLowerCase();
        return SUPPORTED_ROLE_STATUSES.contains(normalized) ? normalized : "active";
    }

    private boolean resolveMemoryEnabled(RoleEntity role) {
        if (role == null || role.getConfigJson() == null) {
            return false;
        }
        return Boolean.TRUE.equals(role.getConfigJson().getMemoryEnabled());
    }

    private List<Map<String, Object>> resolveMcpModules(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }

        Map<String, McpServerEntity> byRef = new HashMap<>();
        List<Long> idRefs = new ArrayList<>();
        List<String> nameRefs = new ArrayList<>();

        for (String ref : refs) {
            if (!StringUtils.hasText(ref)) {
                continue;
            }
            String trimmedRef = ref.trim();
            try {
                idRefs.add(Long.parseLong(trimmedRef));
            } catch (NumberFormatException ex) {
                nameRefs.add(trimmedRef);
            }
        }

        if (!idRefs.isEmpty()) {
            List<McpServerEntity> mcpByIds = mcpServerService.list(
                    new LambdaQueryWrapper<McpServerEntity>().in(McpServerEntity::getId, idRefs)
            );
            for (McpServerEntity entity : mcpByIds) {
                byRef.put(String.valueOf(entity.getId()), entity);
            }
        }

        if (!nameRefs.isEmpty()) {
            List<McpServerEntity> mcpByNames = mcpServerService.list(
                    new LambdaQueryWrapper<McpServerEntity>().in(McpServerEntity::getName, nameRefs)
            );
            for (McpServerEntity entity : mcpByNames) {
                byRef.put(entity.getName(), entity);
            }
        }

        List<Map<String, Object>> modules = new ArrayList<>();
        for (String ref : refs) {
            if (!StringUtils.hasText(ref)) {
                continue;
            }
            String trimmedRef = ref.trim();
            McpServerEntity entity = byRef.get(trimmedRef);
            if (entity != null) {
                modules.add(buildModule(String.valueOf(entity.getId()), entity.getName(), entity.getDescription()));
            } else {
                modules.add(buildModule(trimmedRef, trimmedRef, "MCP 配置不存在"));
            }
        }
        return modules;
    }

    private List<Map<String, Object>> resolveSkillModules(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }

        Map<String, SkillEntity> byRef = new HashMap<>();
        List<Long> idRefs = new ArrayList<>();
        List<String> nameRefs = new ArrayList<>();

        for (String ref : refs) {
            if (!StringUtils.hasText(ref)) {
                continue;
            }
            String trimmedRef = ref.trim();
            try {
                idRefs.add(Long.parseLong(trimmedRef));
            } catch (NumberFormatException ex) {
                nameRefs.add(trimmedRef);
            }
        }

        if (!idRefs.isEmpty()) {
            List<SkillEntity> skillsByIds = skillService.list(
                    new LambdaQueryWrapper<SkillEntity>().in(SkillEntity::getId, idRefs)
            );
            for (SkillEntity entity : skillsByIds) {
                byRef.put(String.valueOf(entity.getId()), entity);
            }
        }

        if (!nameRefs.isEmpty()) {
            List<SkillEntity> skillsByNames = skillService.list(
                    new LambdaQueryWrapper<SkillEntity>().in(SkillEntity::getName, nameRefs)
            );
            for (SkillEntity entity : skillsByNames) {
                byRef.put(entity.getName(), entity);
            }
        }

        List<Map<String, Object>> modules = new ArrayList<>();
        for (String ref : refs) {
            if (!StringUtils.hasText(ref)) {
                continue;
            }
            String trimmedRef = ref.trim();
            SkillEntity entity = byRef.get(trimmedRef);
            if (entity != null) {
                modules.add(buildModule(String.valueOf(entity.getId()), entity.getName(), entity.getDescription()));
            } else {
                modules.add(buildModule(trimmedRef, trimmedRef, "Skill 配置不存在"));
            }
        }
        return modules;
    }

    private Map<String, Object> buildModule(String id, String name, String desc) {
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("id", id);
        module.put("name", StringUtils.hasText(name) ? name : id);
        module.put("desc", StringUtils.hasText(desc) ? desc : "");
        return module;
    }

    /**
     * 查询岗位下存活的 Pod 状态
     *
     * 返回 runningPods（名称列表）、podCount、maxPodCount、alive（是否有存活 Pod）。
     */
    @GetMapping("/{id:\\d+}/pods")
    public Map<String, Object> getRolePods(@PathVariable Long id) {
        String serviceId = String.valueOf(id);

        List<String> runningPods;
        try {
            runningPods = k8sOrchestrator.getRunningPods(serviceId);
        } catch (Exception e) {
            runningPods = List.of();
        }

        var snapshot = snapshotService.getSnapshot(serviceId);
        int maxPodCount = snapshot != null && snapshot.getMaxPodCount() != null
                ? snapshot.getMaxPodCount() : 0;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("roleId", id.toString());
        data.put("serviceId", serviceId);
        data.put("alive", !runningPods.isEmpty());
        data.put("podCount", runningPods.size());
        data.put("maxPodCount", maxPodCount);
        data.put("runningPods", runningPods);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "success");
        response.put("data", data);
        return response;
    }

    @PostMapping
    public Map<String, Object> createRole(@RequestBody RoleEntity role) {
        RoleEntity created = roleService.createRole(role, getCurrentUserId(), getCurrentUsername());

        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "success");
        response.put("data", Map.of("id", created.getId().toString()));
        return response;
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateRole(@PathVariable Long id, @RequestBody RoleEntity role) {
        roleService.updateRole(id, role, getCurrentUserId());

        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "success");
        return response;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteRole(@PathVariable Long id) {
        roleService.deleteRole(id, getCurrentUserId());
        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "success");
        return response;
    }

    @PostMapping("/{id}/favorite")
    public Map<String, Object> toggleFavorite(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        boolean favorite = body.getOrDefault("favorite", false);
        roleService.toggleFavorite(id, getCurrentUserId(), favorite);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 0);
        response.put("msg", "success");
        return response;
    }
}
