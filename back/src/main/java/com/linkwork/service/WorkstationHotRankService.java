package com.linkwork.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class WorkstationHotRankService {

    public static final String HOT_RANK_KEY = "rank:workstations:favorite";

    private final StringRedisTemplate redisTemplate;

    public WorkstationHotRankService(@Nullable StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void incrementFavoriteScore(Long workstationId, double delta) {
        if (workstationId == null || delta == 0D || redisTemplate == null) return;
        String member = String.valueOf(workstationId);
        try {
            Double score = redisTemplate.opsForZSet().incrementScore(HOT_RANK_KEY, member, delta);
            if (score != null && score <= 0D) {
                redisTemplate.opsForZSet().remove(HOT_RANK_KEY, member);
            }
        } catch (Exception e) {
            log.warn("update workstation hot rank failed, wsId={}, delta={}: {}", workstationId, delta, e.getMessage());
        }
    }

    public List<Long> listTopWorkstationIds(int limit) {
        if (limit <= 0 || redisTemplate == null) return List.of();
        try {
            var members = redisTemplate.opsForZSet().reverseRange(HOT_RANK_KEY, 0, limit - 1L);
            if (members == null || members.isEmpty()) return List.of();
            List<Long> result = new ArrayList<>(members.size());
            for (String member : members) {
                try {
                    result.add(Long.parseLong(member));
                } catch (NumberFormatException ignored) {}
            }
            return result;
        } catch (Exception e) {
            log.warn("query workstation hot rank failed: {}", e.getMessage());
            return List.of();
        }
    }

    public void rebuildRank(Map<Long, Long> favoriteCountMap) {
        if (redisTemplate == null || favoriteCountMap == null) return;
        try {
            redisTemplate.delete(HOT_RANK_KEY);
            favoriteCountMap.forEach((wsId, count) -> {
                if (wsId == null || count == null || count <= 0L) return;
                redisTemplate.opsForZSet().add(HOT_RANK_KEY, String.valueOf(wsId), count.doubleValue());
            });
        } catch (Exception e) {
            log.warn("rebuild workstation hot rank failed: {}", e.getMessage());
        }
    }
}
