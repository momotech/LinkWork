package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkwork.mapper.McpUsageDailyMapper;
import com.linkwork.model.entity.McpUsageDailyEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 每小时将 Redis 中的 MCP 使用量数据聚合到 linkwork_mcp_usage_daily 表。
 * <p>
 * Redis key 格式:
 * - mcp:usage:user:{YYYYMMDD}  field = "{userId}:{mcpName}"  value = count
 * - mcp:usage:bytes:{YYYYMMDD} field = "{taskId}:{mcpName}:req"  value = bytes
 *   (bytes 按 user 维度聚合困难, 这里仅聚合 user 维度的 count)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpUsageAggregator {

    private final StringRedisTemplate redisTemplate;
    private final McpUsageDailyMapper usageDailyMapper;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Scheduled(cron = "0 5 * * * *")
    public void aggregateCurrentDay() {
        String dateStr = LocalDate.now().format(DATE_FMT);
        aggregateDate(dateStr);
    }

    @Scheduled(cron = "0 10 0 * * *")
    public void aggregateYesterday() {
        String dateStr = LocalDate.now().minusDays(1).format(DATE_FMT);
        aggregateDate(dateStr);
    }

    void aggregateDate(String dateStr) {
        String userKey = "mcp:usage:user:" + dateStr;
        Map<Object, Object> entries;

        try {
            entries = redisTemplate.opsForHash().entries(userKey);
        } catch (Exception e) {
            log.warn("Failed to read Redis usage data for {}: {}", dateStr, e.getMessage());
            return;
        }

        if (entries == null || entries.isEmpty()) {
            return;
        }

        LocalDate date = LocalDate.parse(dateStr, DATE_FMT);
        int upserted = 0;

        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String field = entry.getKey().toString();
            String value = entry.getValue().toString();

            String[] parts = field.split(":", 2);
            if (parts.length < 2) continue;
            String userId = parts[0];
            String mcpName = parts[1];
            if (!StringUtils.hasText(userId) || !StringUtils.hasText(mcpName)) continue;

            int count;
            try {
                count = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                continue;
            }

            try {
                upsertUsageDaily(date, userId, mcpName, count);
                upserted++;
            } catch (Exception e) {
                log.warn("Failed to upsert usage for date={}, user={}, mcp={}: {}",
                        dateStr, userId, mcpName, e.getMessage());
            }
        }

        log.info("MCP usage aggregation completed: date={}, records={}", dateStr, upserted);
    }

    private void upsertUsageDaily(LocalDate date, String userId, String mcpName, int callCount) {
        LambdaQueryWrapper<McpUsageDailyEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(McpUsageDailyEntity::getDate, date)
                .eq(McpUsageDailyEntity::getUserId, userId)
                .eq(McpUsageDailyEntity::getMcpName, mcpName);

        McpUsageDailyEntity existing = usageDailyMapper.selectOne(wrapper);
        if (existing != null) {
            existing.setCallCount(callCount);
            usageDailyMapper.updateById(existing);
        } else {
            McpUsageDailyEntity entity = new McpUsageDailyEntity();
            entity.setDate(date);
            entity.setUserId(userId);
            entity.setMcpName(mcpName);
            entity.setCallCount(callCount);
            entity.setReqBytes(0L);
            entity.setRespBytes(0L);
            usageDailyMapper.insert(entity);
        }
    }
}
