package com.linkwork.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 热门岗位排行榜（Redis Sorted Set）
 */
@Slf4j
@Service
public class RoleHotRankService {

    public static final String HOT_ROLE_RANK_KEY = "rank:roles:favorite";

    private final StringRedisTemplate redisTemplate;

    public RoleHotRankService(@Nullable StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void incrementFavoriteScore(Long roleId, double delta) {
        if (roleId == null || delta == 0D || redisTemplate == null) {
            return;
        }
        String member = String.valueOf(roleId);
        try {
            Double score = redisTemplate.opsForZSet().incrementScore(HOT_ROLE_RANK_KEY, member, delta);
            if (score != null && score <= 0D) {
                redisTemplate.opsForZSet().remove(HOT_ROLE_RANK_KEY, member);
            }
        } catch (Exception e) {
            log.warn("update role hot rank failed, roleId={}, delta={}, reason={}", roleId, delta, e.getMessage());
        }
    }

    public List<Long> listTopRoleIds(int limit) {
        if (limit <= 0 || redisTemplate == null) {
            return List.of();
        }
        try {
            var members = redisTemplate.opsForZSet().reverseRange(HOT_ROLE_RANK_KEY, 0, limit - 1L);
            if (members == null || members.isEmpty()) {
                return List.of();
            }
            List<Long> result = new ArrayList<>(members.size());
            for (String member : members) {
                try {
                    result.add(Long.parseLong(member));
                } catch (NumberFormatException ignored) {
                    // 非法 member 直接跳过，避免影响榜单读取
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("query role hot rank failed, reason={}", e.getMessage());
            return List.of();
        }
    }

    public void rebuildRank(Map<Long, Long> favoriteCountMap) {
        if (redisTemplate == null || favoriteCountMap == null) {
            return;
        }
        try {
            redisTemplate.delete(HOT_ROLE_RANK_KEY);
            favoriteCountMap.forEach((roleId, favoriteCount) -> {
                if (roleId == null || favoriteCount == null || favoriteCount <= 0L) {
                    return;
                }
                redisTemplate.opsForZSet().add(HOT_ROLE_RANK_KEY, String.valueOf(roleId), favoriteCount.doubleValue());
            });
        } catch (Exception e) {
            log.warn("rebuild role hot rank failed, reason={}", e.getMessage());
        }
    }
}

