package com.linkwork.service;

import com.linkwork.model.entity.LinkworkCronJob;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class CronExpressionHelper {

    public String normalizeScheduleType(String scheduleType) {
        if (!StringUtils.hasText(scheduleType)) {
            throw new IllegalArgumentException("scheduleType is required");
        }
        return scheduleType.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizeTimezone(String timezone) {
        if (!StringUtils.hasText(timezone)) {
            return "Asia/Shanghai";
        }
        String tz = timezone.trim();
        try {
            ZoneId.of(tz);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid timezone: " + timezone);
        }
        return tz;
    }

    public void validateSchedule(String scheduleType,
                                 String cronExpr,
                                 Long intervalMs,
                                 LocalDateTime runAt,
                                 String timezone) {
        String type = normalizeScheduleType(scheduleType);
        ZoneId zoneId = ZoneId.of(normalizeTimezone(timezone));
        LocalDateTime now = LocalDateTime.now(zoneId);

        switch (type) {
            case "cron" -> {
                if (!StringUtils.hasText(cronExpr)) {
                    throw new IllegalArgumentException("cronExpr is required when scheduleType=cron");
                }
                try {
                    CronExpression expr = CronExpression.parse(cronExpr.trim());
                    ZonedDateTime from = ZonedDateTime.of(now.withSecond(0).withNano(0), zoneId);
                    ZonedDateTime next = expr.next(from);
                    if (next == null) {
                        throw new IllegalArgumentException("cronExpr cannot compute next fire time");
                    }
                    if (java.time.Duration.between(from, next).toMillis() < 60_000) {
                        throw new IllegalArgumentException("cron minimum granularity is 1 minute");
                    }
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid cronExpr: " + e.getMessage());
                }
            }
            case "every" -> {
                if (intervalMs == null) {
                    throw new IllegalArgumentException("intervalMs is required when scheduleType=every");
                }
                if (intervalMs < 60_000) {
                    throw new IllegalArgumentException("intervalMs minimum is 60000");
                }
            }
            case "at" -> {
                if (runAt == null) {
                    throw new IllegalArgumentException("runAt is required when scheduleType=at");
                }
                if (!runAt.isAfter(now)) {
                    throw new IllegalArgumentException("runAt must be in the future");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported scheduleType: " + scheduleType);
        }
    }

    public LocalDateTime computeFirstFireTime(String scheduleType,
                                              String cronExpr,
                                              Long intervalMs,
                                              LocalDateTime runAt,
                                              String timezone) {
        String type = normalizeScheduleType(scheduleType);
        ZoneId zoneId = ZoneId.of(normalizeTimezone(timezone));
        LocalDateTime now = LocalDateTime.now(zoneId).withSecond(0).withNano(0);
        return switch (type) {
            case "cron" -> {
                CronExpression expr = CronExpression.parse(cronExpr.trim());
                ZonedDateTime next = expr.next(ZonedDateTime.of(now, zoneId));
                yield next == null ? null : next.toLocalDateTime();
            }
            case "every" -> now.plusNanos(intervalMs * 1_000_000);
            case "at" -> runAt;
            default -> throw new IllegalArgumentException("Unsupported scheduleType: " + scheduleType);
        };
    }

    public LocalDateTime computeNextFireTime(LinkworkCronJob job, LocalDateTime currentFireTime) {
        String type = normalizeScheduleType(job.getScheduleType());
        ZoneId zoneId = ZoneId.of(normalizeTimezone(job.getTimezone()));
        LocalDateTime base = currentFireTime == null
                ? LocalDateTime.now(zoneId).withSecond(0).withNano(0)
                : currentFireTime;

        return switch (type) {
            case "cron" -> {
                CronExpression expr = CronExpression.parse(job.getCronExpr().trim());
                ZonedDateTime next = expr.next(ZonedDateTime.of(base, zoneId));
                yield next == null ? null : next.toLocalDateTime();
            }
            case "every" -> base.plusNanos(job.getIntervalMs() * 1_000_000);
            case "at" -> null;
            default -> null;
        };
    }

    public List<String> previewNextFireTimes(String scheduleType,
                                             String cronExpr,
                                             Long intervalMs,
                                             LocalDateTime runAt,
                                             String timezone,
                                             int limit) {
        String type = normalizeScheduleType(scheduleType);
        ZoneId zoneId = ZoneId.of(normalizeTimezone(timezone));
        List<String> times = new ArrayList<>();
        int size = Math.max(limit, 0);
        if (size == 0) return times;

        if ("at".equals(type)) {
            if (runAt != null) times.add(runAt.toString());
            return times;
        }

        LocalDateTime cursor = LocalDateTime.now(zoneId).withSecond(0).withNano(0);
        if ("every".equals(type)) {
            for (int i = 0; i < size; i++) {
                cursor = cursor.plusNanos(intervalMs * 1_000_000);
                times.add(cursor.toString());
            }
            return times;
        }

        CronExpression expr = CronExpression.parse(cronExpr.trim());
        ZonedDateTime zonedCursor = ZonedDateTime.of(cursor, zoneId);
        for (int i = 0; i < size; i++) {
            zonedCursor = expr.next(zonedCursor);
            if (zonedCursor == null) break;
            times.add(zonedCursor.toLocalDateTime().toString());
        }
        return times;
    }
}
