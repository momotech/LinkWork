package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.linkwork.common.exception.ForbiddenOperationException;
import com.linkwork.common.exception.ResourceNotFoundException;
import com.linkwork.mapper.UserFavoriteWorkstationMapper;
import com.linkwork.mapper.WorkstationMapper;
import com.linkwork.model.entity.UserFavoriteWorkstationEntity;
import com.linkwork.model.entity.WorkstationEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WorkstationV1Service extends ServiceImpl<WorkstationMapper, WorkstationEntity> {

    private static final Set<String> SUPPORTED_STATUSES = Set.of("active", "maintenance", "disabled");

    @Autowired
    private UserFavoriteWorkstationMapper favoriteMapper;
    @Autowired
    private CronJobV1Service cronJobService;
    @Autowired
    private WorkstationHotRankService hotRankService;
    @Autowired
    private AdminAccessService adminAccessService;

    public Page<WorkstationEntity> listWorkstations(int page, int pageSize, String query,
                                                     String category, String scope, String status,
                                                     String currentUserId) {
        Page<WorkstationEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<WorkstationEntity> wrapper = buildQueryWrapper(query, category, scope, status, currentUserId);
        wrapper.orderByDesc(WorkstationEntity::getCreatedAt);
        return this.page(pageParam, wrapper);
    }

    public List<WorkstationEntity> listHotWorkstations(int limit, String currentUserId) {
        int safeLimit = Math.max(1, limit);
        LambdaQueryWrapper<WorkstationEntity> wrapper = buildQueryWrapper(null, null, "all", null, currentUserId);
        List<WorkstationEntity> list = this.list(wrapper).stream()
                .filter(Objects::nonNull)
                .filter(ws -> ws.getId() != null)
                .collect(Collectors.toList());
        if (list.isEmpty()) return List.of();

        Map<Long, WorkstationEntity> wsMap = list.stream()
                .collect(Collectors.toMap(WorkstationEntity::getId, ws -> ws));

        int rankFetchSize = Math.max(20, safeLimit * 5);
        List<Long> rankedIds = hotRankService.listTopWorkstationIds(rankFetchSize);
        if (rankedIds.isEmpty()) {
            hotRankService.rebuildRank(queryAllFavoriteCountMap());
            rankedIds = hotRankService.listTopWorkstationIds(rankFetchSize);
        }

        LinkedHashSet<Long> ordered = new LinkedHashSet<>();
        for (Long id : rankedIds) {
            if (wsMap.containsKey(id)) ordered.add(id);
            if (ordered.size() >= safeLimit) break;
        }

        if (ordered.size() < safeLimit) {
            Map<Long, Long> countMap = queryFavoriteCountMap(
                    list.stream().map(WorkstationEntity::getId).collect(Collectors.toList()));
            list.stream()
                    .sorted(Comparator.comparingLong((WorkstationEntity ws) -> countMap.getOrDefault(ws.getId(), 0L))
                            .reversed()
                            .thenComparing(WorkstationEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .forEach(ws -> {
                        ordered.add(ws.getId());
                    });
        }

        return ordered.stream()
                .limit(safeLimit)
                .map(wsMap::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public WorkstationEntity createWorkstation(WorkstationEntity ws, String userId, String username) {
        assertNameUnique(ws.getName(), null);
        ws.setWorkstationNo("WS-" + System.currentTimeMillis());
        ws.setCreatorId(userId);
        ws.setCreatorName(username);
        ws.setStatus(normalizeStatusOrDefault(ws.getStatus()));
        if (ws.getIsPublic() == null) ws.setIsPublic(false);
        if (ws.getMaxEmployees() == null) ws.setMaxEmployees(1);
        this.save(ws);
        return ws;
    }

    public WorkstationEntity updateWorkstation(Long id, WorkstationEntity updateInfo, String userId) {
        WorkstationEntity exists = getForWrite(id, userId);
        String previousStatus = exists.getStatus();

        if (StringUtils.hasText(updateInfo.getName())) {
            String name = updateInfo.getName().trim();
            if (!name.equals(exists.getName())) assertNameUnique(name, exists.getId());
            exists.setName(name);
        }
        if (StringUtils.hasText(updateInfo.getDescription())) exists.setDescription(updateInfo.getDescription());
        if (StringUtils.hasText(updateInfo.getCategory())) exists.setCategory(updateInfo.getCategory());
        if (StringUtils.hasText(updateInfo.getIcon())) exists.setIcon(updateInfo.getIcon());
        if (StringUtils.hasText(updateInfo.getImage())) exists.setImage(updateInfo.getImage());
        if (updateInfo.getPrompt() != null) exists.setPrompt(updateInfo.getPrompt().trim());
        if (updateInfo.getConfigJson() != null) exists.setConfigJson(updateInfo.getConfigJson());
        if (updateInfo.getIsPublic() != null) exists.setIsPublic(updateInfo.getIsPublic());
        if (updateInfo.getMaxEmployees() != null) exists.setMaxEmployees(updateInfo.getMaxEmployees());
        if (StringUtils.hasText(updateInfo.getStatus())) exists.setStatus(normalizeStatus(updateInfo.getStatus()));
        exists.setUpdaterId(userId);
        this.updateById(exists);

        if ("active".equalsIgnoreCase(previousStatus) && !"active".equalsIgnoreCase(exists.getStatus())) {
            cronJobService.disableByWorkstationId(exists.getId(), "workstation status changed to " + exists.getStatus());
        }
        return exists;
    }

    public void deleteWorkstation(Long id, String userId) {
        WorkstationEntity ws = getForWrite(id, userId);
        cronJobService.disableByWorkstationId(ws.getId(), "workstation deleted");
        this.removeById(id);
    }

    public boolean isFavorite(Long wsId, String userId) {
        if (wsId == null || !StringUtils.hasText(userId)) return false;
        return favoriteMapper.exists(
                new LambdaQueryWrapper<UserFavoriteWorkstationEntity>()
                        .eq(UserFavoriteWorkstationEntity::getWorkstationId, wsId)
                        .eq(UserFavoriteWorkstationEntity::getUserId, userId));
    }

    @Transactional
    public boolean toggleFavorite(Long wsId, String userId, boolean favorite) {
        getForRead(wsId, userId);
        if (favorite) {
            if (!isFavorite(wsId, userId)) {
                UserFavoriteWorkstationEntity entity = new UserFavoriteWorkstationEntity();
                entity.setUserId(userId);
                entity.setWorkstationId(wsId);
                favoriteMapper.insert(entity);
                hotRankService.incrementFavoriteScore(wsId, 1D);
            }
            return true;
        } else {
            int deleted = favoriteMapper.delete(
                    new LambdaQueryWrapper<UserFavoriteWorkstationEntity>()
                            .eq(UserFavoriteWorkstationEntity::getWorkstationId, wsId)
                            .eq(UserFavoriteWorkstationEntity::getUserId, userId));
            if (deleted > 0) hotRankService.incrementFavoriteScore(wsId, -1D);
            return false;
        }
    }

    public WorkstationEntity getForRead(Long id, String userId) {
        WorkstationEntity ws = this.getById(id);
        if (ws == null) throw new ResourceNotFoundException("Workstation not found: " + id);
        if (!canRead(ws, userId)) throw new ForbiddenOperationException("No permission to access this workstation");
        return ws;
    }

    public WorkstationEntity getForWrite(Long id, String userId) {
        WorkstationEntity ws = this.getById(id);
        if (ws == null) throw new ResourceNotFoundException("Workstation not found: " + id);
        if (!canWrite(ws, userId)) throw new ForbiddenOperationException("Only creator or admin can modify");
        return ws;
    }

    public Map<Long, Long> queryFavoriteCountMap(List<Long> wsIds) {
        if (wsIds == null || wsIds.isEmpty()) return Map.of();
        QueryWrapper<UserFavoriteWorkstationEntity> wrapper = new QueryWrapper<>();
        wrapper.select("workstation_id AS wsId", "COUNT(1) AS cnt")
                .in("workstation_id", wsIds)
                .groupBy("workstation_id");
        List<Map<String, Object>> rows = favoriteMapper.selectMaps(wrapper);
        Map<Long, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object idVal = row.getOrDefault("wsId", row.get("workstation_id"));
            if (!(idVal instanceof Number n)) continue;
            Object cntVal = row.getOrDefault("cnt", row.get("COUNT(1)"));
            long cnt = cntVal instanceof Number c ? c.longValue() : 0L;
            result.put(n.longValue(), cnt);
        }
        return result;
    }

    public Map<Long, Long> queryAllFavoriteCountMap() {
        QueryWrapper<UserFavoriteWorkstationEntity> wrapper = new QueryWrapper<>();
        wrapper.select("workstation_id AS wsId", "COUNT(1) AS cnt")
                .groupBy("workstation_id");
        List<Map<String, Object>> rows = favoriteMapper.selectMaps(wrapper);
        Map<Long, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object idVal = row.getOrDefault("wsId", row.get("workstation_id"));
            if (!(idVal instanceof Number n)) continue;
            Object cntVal = row.getOrDefault("cnt", row.get("COUNT(1)"));
            long cnt = cntVal instanceof Number c ? c.longValue() : 0L;
            result.put(n.longValue(), cnt);
        }
        return result;
    }

    private LambdaQueryWrapper<WorkstationEntity> buildQueryWrapper(String query, String category,
                                                                     String scope, String status, String userId) {
        LambdaQueryWrapper<WorkstationEntity> w = new LambdaQueryWrapper<>();
        boolean admin = adminAccessService.isAdmin(userId);

        if (StringUtils.hasText(category)) w.eq(WorkstationEntity::getCategory, category);
        if (StringUtils.hasText(query)) {
            w.and(q -> q.like(WorkstationEntity::getName, query)
                    .or().like(WorkstationEntity::getDescription, query));
        }
        if (StringUtils.hasText(status)) w.eq(WorkstationEntity::getStatus, normalizeStatus(status));

        if ("mine".equalsIgnoreCase(scope)) {
            w.eq(WorkstationEntity::getCreatorId, userId);
        } else if ("favorite".equalsIgnoreCase(scope)) {
            if (!StringUtils.hasText(userId)) { w.eq(WorkstationEntity::getId, -1L); return w; }
            List<Long> favIds = favoriteMapper.selectList(
                    new LambdaQueryWrapper<UserFavoriteWorkstationEntity>()
                            .eq(UserFavoriteWorkstationEntity::getUserId, userId))
                    .stream().map(UserFavoriteWorkstationEntity::getWorkstationId).toList();
            if (favIds.isEmpty()) { w.eq(WorkstationEntity::getId, -1L); return w; }
            w.in(WorkstationEntity::getId, favIds);
        } else {
            if (!admin) {
                if (StringUtils.hasText(userId)) {
                    w.and(q -> q.eq(WorkstationEntity::getIsPublic, true)
                            .or().eq(WorkstationEntity::getCreatorId, userId));
                } else {
                    w.eq(WorkstationEntity::getIsPublic, true);
                }
            }
        }
        return w;
    }

    private boolean canRead(WorkstationEntity ws, String userId) {
        return adminAccessService.isAdmin(userId) || Boolean.TRUE.equals(ws.getIsPublic()) || isOwner(ws, userId);
    }

    private boolean canWrite(WorkstationEntity ws, String userId) {
        return adminAccessService.isAdmin(userId) || isOwner(ws, userId);
    }

    private boolean isOwner(WorkstationEntity ws, String userId) {
        return StringUtils.hasText(userId) && userId.equals(ws.getCreatorId());
    }

    private void assertNameUnique(String name, Long excludeId) {
        if (!StringUtils.hasText(name)) throw new IllegalArgumentException("Workstation name is required");
        LambdaQueryWrapper<WorkstationEntity> w = new LambdaQueryWrapper<WorkstationEntity>()
                .eq(WorkstationEntity::getName, name.trim());
        if (excludeId != null) w.ne(WorkstationEntity::getId, excludeId);
        if (this.count(w) > 0) throw new IllegalArgumentException("Workstation name already exists: " + name.trim());
    }

    private String normalizeStatusOrDefault(String raw) {
        String s = normalizeStatus(raw);
        return StringUtils.hasText(s) ? s : "active";
    }

    private String normalizeStatus(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        String s = raw.trim().toLowerCase();
        if (!SUPPORTED_STATUSES.contains(s)) throw new IllegalArgumentException("Invalid status: " + raw);
        return s;
    }
}
