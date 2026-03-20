package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linkwork.common.ForbiddenOperationException;
import com.linkwork.common.ResourceNotFoundException;
import com.linkwork.mapper.RoleMapper;
import com.linkwork.mapper.UserFavoriteRoleMapper;
import com.linkwork.model.entity.RoleEntity;
import com.linkwork.model.entity.UserFavoriteRoleEntity;
import com.linkwork.model.enums.DeployMode;
import com.linkwork.model.enums.PodMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RoleService extends ServiceImpl<RoleMapper, RoleEntity> {

    @Autowired
    private UserFavoriteRoleMapper userFavoriteRoleMapper;
    @Autowired
    private CronJobService cronJobService;
    @Autowired
    private RoleHotRankService roleHotRankService;
    @Autowired
    private AdminAccessService adminAccessService;

    private static final Set<String> SUPPORTED_RUNTIME_MODES = Set.of(PodMode.SIDECAR.name(), PodMode.ALONE.name());
    private static final Set<String> SUPPORTED_DEPLOY_MODES = Set.of(DeployMode.K8S.name(), DeployMode.COMPOSE.name());
    private static final Set<String> SUPPORTED_ROLE_STATUSES = Set.of("active", "maintenance", "disabled");

    /**
     * 分页查询岗位
     * @param page 页码
     * @param pageSize 每页条数
     * @param query 搜索关键词
     * @param category 分类
     * @param scope 范围: all, mine, favorite
     * @param status 状态筛选: active, disabled, maintenance
     * @param currentUserId 当前用户ID
     * @return 分页结果
     */
    public Page<RoleEntity> listRoles(int page, int pageSize, String query, String category, String scope, String status, String currentUserId) {
        Page<RoleEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<RoleEntity> wrapper = buildRoleQueryWrapper(query, category, scope, status, currentUserId);

        // 3. 排序 (默认按时间倒序)
        wrapper.orderByDesc(RoleEntity::getCreatedAt);

        return this.page(pageParam, wrapper);
    }

    /**
     * 查询热门岗位（按收藏数倒序，最多 limit 条）
     */
    public List<RoleEntity> listHotRoles(int limit, String currentUserId) {
        int safeLimit = Math.max(1, limit);
        LambdaQueryWrapper<RoleEntity> wrapper = buildRoleQueryWrapper(null, null, "all", null, currentUserId);
        List<RoleEntity> roles = this.list(wrapper).stream()
                .filter(Objects::nonNull)
                .filter(role -> role.getId() != null)
                .collect(Collectors.toList());
        if (roles.isEmpty()) {
            return List.of();
        }

        Map<Long, RoleEntity> roleMap = roles.stream()
                .collect(Collectors.toMap(RoleEntity::getId, role -> role));

        int rankFetchSize = Math.max(20, safeLimit * 5);
        List<Long> rankedRoleIds = roleHotRankService.listTopRoleIds(rankFetchSize);
        if (rankedRoleIds.isEmpty()) {
            roleHotRankService.rebuildRank(queryAllFavoriteCountMap());
            rankedRoleIds = roleHotRankService.listTopRoleIds(rankFetchSize);
        }

        LinkedHashSet<Long> orderedRoleIds = new LinkedHashSet<>();
        for (Long roleId : rankedRoleIds) {
            if (roleMap.containsKey(roleId)) {
                orderedRoleIds.add(roleId);
            }
            if (orderedRoleIds.size() >= safeLimit) {
                break;
            }
        }

        if (orderedRoleIds.size() < safeLimit) {
            Map<Long, Long> favoriteCountMap = queryFavoriteCountMap(
                    roles.stream().map(RoleEntity::getId).collect(Collectors.toList())
            );
            List<RoleEntity> fallbackSorted = roles.stream()
                    .sorted(
                            Comparator.comparingLong((RoleEntity role) -> favoriteCountMap.getOrDefault(role.getId(), 0L))
                                    .reversed()
                                    .thenComparing(RoleEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    )
                    .collect(Collectors.toList());
            for (RoleEntity role : fallbackSorted) {
                orderedRoleIds.add(role.getId());
                if (orderedRoleIds.size() >= safeLimit) {
                    break;
                }
            }
        }

        List<RoleEntity> orderedRoles = new java.util.ArrayList<>(orderedRoleIds.size());
        for (Long roleId : orderedRoleIds) {
            RoleEntity role = roleMap.get(roleId);
            if (role != null) {
                orderedRoles.add(role);
            }
        }
        return orderedRoles;
    }

    /**
     * 批量查询岗位收藏数
     */
    public Map<Long, Long> queryFavoriteCountMap(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return Map.of();
        }

        QueryWrapper<UserFavoriteRoleEntity> wrapper = new QueryWrapper<>();
        wrapper.select("role_id AS roleId", "COUNT(1) AS favoriteCount")
                .in("role_id", roleIds)
                .groupBy("role_id");

        List<Map<String, Object>> rows = userFavoriteRoleMapper.selectMaps(wrapper);
        Map<Long, Long> favoriteCountMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object roleIdValue = row.getOrDefault("roleId", row.get("role_id"));
            if (!(roleIdValue instanceof Number roleIdNumber)) {
                continue;
            }
            Long roleId = roleIdNumber.longValue();
            Object favoriteCountValue = row.getOrDefault("favoriteCount", row.get("favorite_count"));
            long favoriteCount = favoriteCountValue instanceof Number countNumber ? countNumber.longValue() : 0L;
            favoriteCountMap.put(roleId, favoriteCount);
        }
        return favoriteCountMap;
    }

    public Map<Long, Long> queryAllFavoriteCountMap() {
        QueryWrapper<UserFavoriteRoleEntity> wrapper = new QueryWrapper<>();
        wrapper.select("role_id AS roleId", "COUNT(1) AS favoriteCount")
                .groupBy("role_id");

        List<Map<String, Object>> rows = userFavoriteRoleMapper.selectMaps(wrapper);
        Map<Long, Long> favoriteCountMap = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object roleIdValue = row.getOrDefault("roleId", row.get("role_id"));
            if (!(roleIdValue instanceof Number roleIdNumber)) {
                continue;
            }
            Long roleId = roleIdNumber.longValue();
            Object favoriteCountValue = row.getOrDefault("favoriteCount", row.get("favorite_count"));
            long favoriteCount = favoriteCountValue instanceof Number countNumber ? countNumber.longValue() : 0L;
            favoriteCountMap.put(roleId, favoriteCount);
        }
        return favoriteCountMap;
    }

    private LambdaQueryWrapper<RoleEntity> buildRoleQueryWrapper(
            String query,
            String category,
            String scope,
            String status,
            String currentUserId
    ) {
        LambdaQueryWrapper<RoleEntity> wrapper = new LambdaQueryWrapper<>();
        boolean adminUser = adminAccessService.isAdmin(currentUserId);

        // 1. 基础筛选
        if (StringUtils.hasText(category)) {
            wrapper.eq(RoleEntity::getCategory, category);
        }
        if (StringUtils.hasText(query)) {
            wrapper.and(w -> w.like(RoleEntity::getName, query)
                    .or().like(RoleEntity::getDescription, query));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(RoleEntity::getStatus, normalizeRoleStatus(status));
        }

        // 2. Scope 筛选
        if ("mine".equalsIgnoreCase(scope)) {
            wrapper.eq(RoleEntity::getCreatorId, currentUserId);
        } else if ("favorite".equalsIgnoreCase(scope)) {
            if (!StringUtils.hasText(currentUserId)) {
                wrapper.eq(RoleEntity::getId, -1L);
                return wrapper;
            }
            // 查询用户收藏的 Role IDs
            List<Long> favoriteRoleIds = userFavoriteRoleMapper.selectList(
                    new LambdaQueryWrapper<UserFavoriteRoleEntity>()
                            .eq(UserFavoriteRoleEntity::getUserId, currentUserId)
            ).stream().map(UserFavoriteRoleEntity::getRoleId).collect(Collectors.toList());

            if (favoriteRoleIds.isEmpty()) {
                wrapper.eq(RoleEntity::getId, -1L);
                return wrapper;
            }
            wrapper.in(RoleEntity::getId, favoriteRoleIds);
        } else {
            // all: 管理员可见全部；非管理员可见公开的 OR 自己的
            if (adminUser) {
                return wrapper;
            }
            if (StringUtils.hasText(currentUserId)) {
                wrapper.and(w -> w.eq(RoleEntity::getIsPublic, true)
                        .or().eq(RoleEntity::getCreatorId, currentUserId));
            } else {
                wrapper.eq(RoleEntity::getIsPublic, true);
            }
        }

        return wrapper;
    }

    /**
     * 创建岗位
     */
    public RoleEntity createRole(RoleEntity role, String userId, String username) {
        assertRoleNameUnique(role.getName(), null);
        role.setRoleNo("ROLE-" + System.currentTimeMillis());
        role.setCreatorId(userId);
        role.setCreatorName(username);
        role.setStatus(normalizeRoleStatusOrDefault(role.getStatus()));
        role.setPrompt(normalizeRequiredRolePrompt(role.getPrompt()));
        // 默认不公开
        if (role.getIsPublic() == null) {
            role.setIsPublic(false);
        }
        if (role.getMaxEmployees() == null) {
            role.setMaxEmployees(1);
        }

        role.setConfigJson(normalizeRoleConfig(role.getConfigJson()));

        this.save(role);
        return role;
    }

    /**
     * 更新岗位
     */
    public RoleEntity updateRole(Long id, RoleEntity updateInfo, String userId) {
        RoleEntity exists = getRoleForWrite(id, userId);
        String previousStatus = exists.getStatus();

        // 更新字段
        if (StringUtils.hasText(updateInfo.getName())) {
            String normalizedName = updateInfo.getName().trim();
            if (!normalizedName.equals(exists.getName())) {
                assertRoleNameUnique(normalizedName, exists.getId());
            }
            exists.setName(normalizedName);
        }
        if (StringUtils.hasText(updateInfo.getDescription())) exists.setDescription(updateInfo.getDescription());
        if (StringUtils.hasText(updateInfo.getCategory())) exists.setCategory(updateInfo.getCategory());
        if (StringUtils.hasText(updateInfo.getIcon())) exists.setIcon(updateInfo.getIcon());
        if (StringUtils.hasText(updateInfo.getImage())) exists.setImage(updateInfo.getImage());
        if (updateInfo.getPrompt() != null) exists.setPrompt(normalizeRequiredRolePrompt(updateInfo.getPrompt()));
        if (updateInfo.getConfigJson() != null) exists.setConfigJson(normalizeRoleConfig(updateInfo.getConfigJson()));
        if (updateInfo.getIsPublic() != null) exists.setIsPublic(updateInfo.getIsPublic());
        if (updateInfo.getMaxEmployees() != null) exists.setMaxEmployees(updateInfo.getMaxEmployees());
        if (StringUtils.hasText(updateInfo.getStatus())) {
            exists.setStatus(normalizeRoleStatus(updateInfo.getStatus()));
        }
        exists.setPrompt(normalizeRequiredRolePrompt(exists.getPrompt()));
        
        exists.setUpdaterId(userId);
        // exists.setUpdaterName(username); // Need username passed in

        this.updateById(exists);

        boolean previousActive = "active".equalsIgnoreCase(previousStatus);
        boolean currentInactive = !StringUtils.hasText(exists.getStatus())
                || !"active".equalsIgnoreCase(exists.getStatus());
        boolean transitionedToInactive = previousActive && currentInactive;
        if (transitionedToInactive) {
            cronJobService.disableByRoleId(exists.getId(), "岗位状态变更为 " + exists.getStatus());
        }
        return exists;
    }

    private RoleEntity.RoleConfig normalizeRoleConfig(RoleEntity.RoleConfig config) {
        if (config == null) {
            return null;
        }

        String deployMode = normalizeDeployMode(config.getDeployMode());
        if (!StringUtils.hasText(deployMode)) {
            deployMode = DeployMode.K8S.name();
        }
        config.setDeployMode(deployMode);

        String runtimeMode = normalizeRuntimeMode(config.getRuntimeMode());
        if (DeployMode.COMPOSE.name().equals(deployMode)) {
            runtimeMode = PodMode.ALONE.name();
        }
        config.setRuntimeMode(runtimeMode);

        String runnerImage = normalizeText(config.getRunnerImage());
        if (DeployMode.COMPOSE.name().equals(deployMode) || PodMode.ALONE.name().equals(runtimeMode)) {
            runnerImage = null;
        }
        config.setRunnerImage(runnerImage);
        config.setMemoryEnabled(Boolean.TRUE.equals(config.getMemoryEnabled()));

        return config;
    }

    private String normalizeDeployMode(String deployModeRaw) {
        String deployMode = normalizeText(deployModeRaw);
        if (!StringUtils.hasText(deployMode)) {
            return null;
        }

        String normalizedMode = deployMode.toUpperCase();
        if (!SUPPORTED_DEPLOY_MODES.contains(normalizedMode)) {
            throw new IllegalArgumentException("非法部署模式: " + deployMode + "，仅支持 K8S/COMPOSE");
        }
        return normalizedMode;
    }

    private String normalizeRuntimeMode(String runtimeModeRaw) {
        String runtimeMode = normalizeText(runtimeModeRaw);
        if (!StringUtils.hasText(runtimeMode)) {
            return null;
        }

        String normalizedMode = runtimeMode.toUpperCase();
        if (!SUPPORTED_RUNTIME_MODES.contains(normalizedMode)) {
            throw new IllegalArgumentException("非法运行模式: " + runtimeMode + "，仅支持 SIDECAR/ALONE");
        }
        return normalizedMode;
    }

    private String normalizeText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.trim();
    }

    private String normalizeRoleStatusOrDefault(String rawStatus) {
        String normalized = normalizeRoleStatus(rawStatus);
        return StringUtils.hasText(normalized) ? normalized : "active";
    }

    private String normalizeRequiredRolePrompt(String rawPrompt) {
        String normalized = normalizeText(rawPrompt);
        if (!StringUtils.hasText(normalized)) {
            return "你是一个智能岗位助手，请根据用户需求高质量完成任务。";
        }
        return normalized;
    }

    private String normalizeRoleStatus(String rawStatus) {
        if (!StringUtils.hasText(rawStatus)) {
            return null;
        }
        String normalized = rawStatus.trim().toLowerCase();
        if (!SUPPORTED_ROLE_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("非法岗位状态: " + rawStatus + "，仅支持 active/maintenance/disabled");
        }
        return normalized;
    }

    private void assertRoleNameUnique(String roleName, Long excludeRoleId) {
        if (!StringUtils.hasText(roleName)) {
            throw new IllegalArgumentException("岗位名称不能为空");
        }

        LambdaQueryWrapper<RoleEntity> wrapper = new LambdaQueryWrapper<RoleEntity>()
                .eq(RoleEntity::getName, roleName.trim());
        if (excludeRoleId != null) {
            wrapper.ne(RoleEntity::getId, excludeRoleId);
        }

        long count = this.count(wrapper);
        if (count > 0) {
            throw new IllegalArgumentException("岗位名称已存在: " + roleName.trim());
        }
    }

    /**
     * 删除岗位
     */
    public void deleteRole(Long id, String userId) {
        RoleEntity exists = getRoleForWrite(id, userId);

        cronJobService.disableByRoleId(exists.getId(), "岗位已删除");
        this.removeById(id);
    }

    /**
     * 检查是否收藏
     */
    public boolean isFavorite(Long roleId, String userId) {
        if (roleId == null || !StringUtils.hasText(userId)) {
            return false;
        }
        return userFavoriteRoleMapper.exists(
                new LambdaQueryWrapper<UserFavoriteRoleEntity>()
                        .eq(UserFavoriteRoleEntity::getRoleId, roleId)
                        .eq(UserFavoriteRoleEntity::getUserId, userId)
        );
    }

    public RoleEntity getRoleForRead(Long id, String userId) {
        RoleEntity role = this.getById(id);
        if (role == null) {
            throw new ResourceNotFoundException("岗位不存在: " + id);
        }
        if (!canRead(role, userId)) {
            throw new ForbiddenOperationException("无权限访问该岗位");
        }
        return role;
    }

    public RoleEntity getRoleForWrite(Long id, String userId) {
        RoleEntity role = this.getById(id);
        if (role == null) {
            throw new ResourceNotFoundException("岗位不存在: " + id);
        }
        if (!canWrite(role, userId)) {
            throw new ForbiddenOperationException("仅岗位创建者或管理员可修改");
        }
        return role;
    }

    private boolean canRead(RoleEntity role, String userId) {
        return adminAccessService.isAdmin(userId)
                || Boolean.TRUE.equals(role.getIsPublic())
                || isOwner(role, userId);
    }

    private boolean canWrite(RoleEntity role, String userId) {
        return adminAccessService.isAdmin(userId) || isOwner(role, userId);
    }

    private boolean isOwner(RoleEntity role, String userId) {
        return StringUtils.hasText(userId) && userId.equals(role.getCreatorId());
    }

    /**
     * 切换收藏状态
     */
    @Transactional
    public boolean toggleFavorite(Long roleId, String userId, boolean isFavorite) {
        RoleEntity role = getRoleForRead(roleId, userId);
        if (isFavorite) {
            // 添加收藏
            if (!isFavorite(roleId, userId)) {
                UserFavoriteRoleEntity entity = new UserFavoriteRoleEntity();
                entity.setUserId(userId);
                entity.setRoleId(roleId);
                userFavoriteRoleMapper.insert(entity);
                roleHotRankService.incrementFavoriteScore(role.getId(), 1D);
            }
            return true;
        } else {
            // 取消收藏
            int deletedCount = userFavoriteRoleMapper.delete(
                    new LambdaQueryWrapper<UserFavoriteRoleEntity>()
                            .eq(UserFavoriteRoleEntity::getRoleId, roleId)
                            .eq(UserFavoriteRoleEntity::getUserId, userId)
            );
            if (deletedCount > 0) {
                roleHotRankService.incrementFavoriteScore(role.getId(), -1D);
            }
            return false;
        }
    }
}
