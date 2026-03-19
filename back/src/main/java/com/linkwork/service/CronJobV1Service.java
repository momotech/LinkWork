package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkwork.common.exception.ForbiddenOperationException;
import com.linkwork.common.exception.ResourceNotFoundException;
import com.linkwork.config.CronConfig;
import com.linkwork.mapper.CronJobMapper;
import com.linkwork.mapper.CronJobRunMapper;
import com.linkwork.mapper.WorkstationMapper;
import com.linkwork.model.dto.*;
import com.linkwork.model.entity.LinkworkCronJob;
import com.linkwork.model.entity.LinkworkCronJobRun;
import com.linkwork.model.entity.LinkworkTask;
import com.linkwork.model.entity.WorkstationEntity;
import com.linkwork.model.enums.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class CronJobV1Service {

    private static final String NOTIFY_NONE = "none";

    private final CronJobMapper cronJobMapper;
    private final CronJobRunMapper cronJobRunMapper;
    private final WorkstationMapper workstationMapper;
    private final CronExpressionHelper cronExpressionHelper;
    private final CronConfig cronConfig;

    @Transactional
    public CronJobResponse create(CronJobCreateRequest request, String creatorId, String creatorName) {
        validateWorkstationVisible(request.getWorkstationId(), creatorId);
        cronExpressionHelper.validateSchedule(request.getScheduleType(), request.getCronExpr(),
                request.getIntervalMs(), request.getRunAt(), request.getTimezone());
        enforceQuota(creatorId, request.getWorkstationId());

        WorkstationEntity ws = workstationMapper.selectById(request.getWorkstationId());
        LinkworkCronJob job = new LinkworkCronJob();
        job.setJobName(request.getJobName().trim());
        job.setCreatorId(creatorId);
        job.setCreatorName(StringUtils.hasText(creatorName) ? creatorName : creatorId);
        job.setWorkstationId(request.getWorkstationId());
        job.setWorkstationName(ws.getName());
        job.setModelId(request.getModelId() != null ? request.getModelId().trim() : null);
        job.setScheduleType(cronExpressionHelper.normalizeScheduleType(request.getScheduleType()));
        job.setCronExpr(StringUtils.hasText(request.getCronExpr()) ? request.getCronExpr().trim() : null);
        job.setIntervalMs(request.getIntervalMs());
        job.setRunAt(request.getRunAt());
        job.setTimezone(cronExpressionHelper.normalizeTimezone(request.getTimezone()));
        job.setTaskContent(request.getTaskContent().trim());
        job.setEnabled(1);
        job.setDeleteAfterRun(Boolean.TRUE.equals(request.getDeleteAfterRun()) ? 1 : 0);
        if ("at".equals(job.getScheduleType()) && request.getDeleteAfterRun() == null) {
            job.setDeleteAfterRun(1);
        }
        job.setMaxRetry(normalizeMaxRetry(request.getMaxRetry()));
        job.setConsecutiveFailures(0);
        job.setNotifyMode(NOTIFY_NONE);
        job.setNextFireTime(cronExpressionHelper.computeFirstFireTime(
                job.getScheduleType(), job.getCronExpr(), job.getIntervalMs(), job.getRunAt(), job.getTimezone()));
        job.setTotalRuns(0);
        job.setIsDeleted(0);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());

        cronJobMapper.insert(job);
        return toResponse(job, true);
    }

    public Map<String, Object> listMine(String creatorId, Long wsId, Boolean enabled, String scheduleType,
                                         String keyword, Integer page, Integer pageSize) {
        int pageNum = page == null || page <= 0 ? 1 : page;
        int size = pageSize == null || pageSize <= 0 ? 20 : pageSize;

        LambdaQueryWrapper<LinkworkCronJob> w = new LambdaQueryWrapper<>();
        w.eq(LinkworkCronJob::getIsDeleted, 0)
                .eq(LinkworkCronJob::getCreatorId, creatorId)
                .orderByDesc(LinkworkCronJob::getCreatedAt);
        if (wsId != null) w.eq(LinkworkCronJob::getWorkstationId, wsId);
        if (enabled != null) w.eq(LinkworkCronJob::getEnabled, enabled ? 1 : 0);
        if (StringUtils.hasText(scheduleType)) w.eq(LinkworkCronJob::getScheduleType, cronExpressionHelper.normalizeScheduleType(scheduleType));
        if (StringUtils.hasText(keyword)) w.like(LinkworkCronJob::getJobName, keyword.trim());

        Page<LinkworkCronJob> result = cronJobMapper.selectPage(new Page<>(pageNum, size), w);
        List<CronJobResponse> items = result.getRecords().stream().map(j -> toResponse(j, false)).toList();

        Map<String, Object> pagination = Map.of(
                "page", result.getCurrent(), "pageSize", result.getSize(),
                "total", result.getTotal(), "totalPages", result.getPages());
        return Map.of("items", items, "pagination", pagination);
    }

    public CronJobResponse getDetail(Long id, String creatorId) {
        return toResponse(getOwnedJob(id, creatorId), true);
    }

    @Transactional
    public CronJobResponse update(Long id, CronJobUpdateRequest request, String creatorId) {
        LinkworkCronJob job = getOwnedJob(id, creatorId);
        cronExpressionHelper.validateSchedule(request.getScheduleType(), request.getCronExpr(),
                request.getIntervalMs(), request.getRunAt(), request.getTimezone());

        if (StringUtils.hasText(request.getJobName())) job.setJobName(request.getJobName().trim());
        if (request.getModelId() != null) job.setModelId(request.getModelId().trim());
        if (request.getScheduleType() != null) job.setScheduleType(cronExpressionHelper.normalizeScheduleType(request.getScheduleType()));
        job.setCronExpr(StringUtils.hasText(request.getCronExpr()) ? request.getCronExpr().trim() : null);
        job.setIntervalMs(request.getIntervalMs());
        job.setRunAt(request.getRunAt());
        if (request.getTimezone() != null) job.setTimezone(cronExpressionHelper.normalizeTimezone(request.getTimezone()));
        if (request.getTaskContent() != null) job.setTaskContent(request.getTaskContent().trim());
        if (request.getDeleteAfterRun() != null) job.setDeleteAfterRun(request.getDeleteAfterRun());
        if (request.getMaxRetry() != null) job.setMaxRetry(normalizeMaxRetry(request.getMaxRetry()));
        job.setConsecutiveFailures(0);
        if (job.getEnabled() != null && job.getEnabled() == 1) {
            job.setNextFireTime(cronExpressionHelper.computeFirstFireTime(
                    job.getScheduleType(), job.getCronExpr(), job.getIntervalMs(), job.getRunAt(), job.getTimezone()));
        }
        job.setUpdatedAt(LocalDateTime.now());
        cronJobMapper.updateById(job);
        return toResponse(job, true);
    }

    @Transactional
    public CronJobResponse toggle(Long id, CronJobToggleRequest request, String creatorId) {
        LinkworkCronJob job = getOwnedJob(id, creatorId);
        boolean enabled = Boolean.TRUE.equals(request.getEnabled());
        job.setEnabled(enabled ? 1 : 0);
        if (enabled) {
            cronExpressionHelper.validateSchedule(job.getScheduleType(), job.getCronExpr(),
                    job.getIntervalMs(), job.getRunAt(), job.getTimezone());
            job.setConsecutiveFailures(0);
            job.setNextFireTime(cronExpressionHelper.computeFirstFireTime(
                    job.getScheduleType(), job.getCronExpr(), job.getIntervalMs(), job.getRunAt(), job.getTimezone()));
        } else {
            job.setNextFireTime(null);
        }
        job.setUpdatedAt(LocalDateTime.now());
        cronJobMapper.updateById(job);
        return toResponse(job, true);
    }

    @Transactional
    public void delete(Long id, String creatorId) {
        LinkworkCronJob job = getOwnedJob(id, creatorId);
        cronJobMapper.deleteById(job.getId());
    }

    public Map<String, Object> listRuns(Long cronJobId, String creatorId, Integer page, Integer pageSize) {
        getOwnedJob(cronJobId, creatorId);
        int pageNum = page == null || page <= 0 ? 1 : page;
        int size = pageSize == null || pageSize <= 0 ? 20 : pageSize;
        Page<LinkworkCronJobRun> result = cronJobRunMapper.selectPage(new Page<>(pageNum, size),
                new LambdaQueryWrapper<LinkworkCronJobRun>()
                        .eq(LinkworkCronJobRun::getCronJobId, cronJobId)
                        .orderByDesc(LinkworkCronJobRun::getCreatedAt));
        List<CronJobRunResponse> items = result.getRecords().stream().map(this::toRunResponse).toList();
        Map<String, Object> pagination = Map.of(
                "page", result.getCurrent(), "pageSize", result.getSize(),
                "total", result.getTotal(), "totalPages", result.getPages());
        return Map.of("items", items, "pagination", pagination);
    }

    public List<LinkworkCronJob> findDueJobs(LocalDateTime threshold) {
        return cronJobMapper.selectList(new LambdaQueryWrapper<LinkworkCronJob>()
                .eq(LinkworkCronJob::getEnabled, 1)
                .eq(LinkworkCronJob::getIsDeleted, 0)
                .isNotNull(LinkworkCronJob::getNextFireTime)
                .le(LinkworkCronJob::getNextFireTime, threshold)
                .orderByAsc(LinkworkCronJob::getNextFireTime));
    }

    @Transactional
    public void advanceAfterDispatch(LinkworkCronJob job, LocalDateTime firedAt) {
        LinkworkCronJob update = new LinkworkCronJob();
        update.setId(job.getId());
        update.setTotalRuns((job.getTotalRuns() == null ? 0 : job.getTotalRuns()) + 1);
        update.setUpdatedAt(LocalDateTime.now());
        LocalDateTime next = cronExpressionHelper.computeNextFireTime(job, firedAt);
        if (next == null) {
            update.setNextFireTime(null);
            if (Objects.equals(job.getDeleteAfterRun(), 1) || "at".equals(job.getScheduleType())) {
                update.setEnabled(0);
            }
        } else {
            update.setNextFireTime(next);
        }
        cronJobMapper.updateById(update);
        trimRunHistory(job.getId());
    }

    @Transactional
    public void recordDispatchFailure(LinkworkCronJob job, String error) {
        int failures = (job.getConsecutiveFailures() == null ? 0 : job.getConsecutiveFailures()) + 1;
        LinkworkCronJob update = new LinkworkCronJob();
        update.setId(job.getId());
        update.setConsecutiveFailures(failures);
        update.setLastRunTime(LocalDateTime.now());
        update.setLastRunStatus("FAILED");
        update.setUpdatedAt(LocalDateTime.now());
        if (failures >= normalizeMaxRetry(job.getMaxRetry())) {
            update.setEnabled(0);
            update.setNextFireTime(null);
        }
        cronJobMapper.updateById(update);
    }

    @Transactional
    public void onTaskStatusChanged(LinkworkTask task, TaskStatus status) {
        if (task == null || !"CRON".equalsIgnoreCase(task.getSource()) || task.getCronJobId() == null) return;
        LinkworkCronJobRun run = cronJobRunMapper.selectOne(new LambdaQueryWrapper<LinkworkCronJobRun>()
                .eq(LinkworkCronJobRun::getTaskNo, task.getTaskNo())
                .orderByDesc(LinkworkCronJobRun::getId).last("LIMIT 1"));
        if (run == null) return;

        String mapped = switch (status) {
            case RUNNING -> "RUNNING";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case ABORTED -> "ABORTED";
            default -> null;
        };
        if (mapped == null || isTerminal(run.getStatus())) return;

        LinkworkCronJobRun upd = new LinkworkCronJobRun();
        upd.setId(run.getId());
        upd.setStatus(mapped);
        if ("RUNNING".equals(mapped)) {
            if (run.getStartedAt() == null) upd.setStartedAt(LocalDateTime.now());
            cronJobRunMapper.updateById(upd);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        upd.setFinishedAt(now);
        if (task.getDurationMs() != null && task.getDurationMs() > 0) {
            upd.setDurationMs(task.getDurationMs());
        } else if (run.getStartedAt() != null) {
            upd.setDurationMs(Duration.between(run.getStartedAt(), now).toMillis());
        }
        if (status == TaskStatus.FAILED) upd.setErrorMessage(task.getReportJson());
        cronJobRunMapper.updateById(upd);

        LinkworkCronJob job = cronJobMapper.selectById(task.getCronJobId());
        if (job == null) return;
        LinkworkCronJob jobUpd = new LinkworkCronJob();
        jobUpd.setId(job.getId());
        jobUpd.setLastRunTime(now);
        jobUpd.setLastRunStatus(mapped);
        jobUpd.setUpdatedAt(now);
        if (status == TaskStatus.COMPLETED) {
            jobUpd.setConsecutiveFailures(0);
        } else if (status == TaskStatus.FAILED) {
            int f = (job.getConsecutiveFailures() == null ? 0 : job.getConsecutiveFailures()) + 1;
            jobUpd.setConsecutiveFailures(f);
            if (f >= normalizeMaxRetry(job.getMaxRetry())) {
                jobUpd.setEnabled(0);
                jobUpd.setNextFireTime(null);
            }
        }
        cronJobMapper.updateById(jobUpd);
    }

    @Transactional
    public void disableByWorkstationId(Long wsId, String reason) {
        if (wsId == null) return;
        List<LinkworkCronJob> jobs = cronJobMapper.selectList(new LambdaQueryWrapper<LinkworkCronJob>()
                .eq(LinkworkCronJob::getWorkstationId, wsId)
                .eq(LinkworkCronJob::getIsDeleted, 0)
                .eq(LinkworkCronJob::getEnabled, 1));
        for (LinkworkCronJob job : jobs) {
            LinkworkCronJob upd = new LinkworkCronJob();
            upd.setId(job.getId());
            upd.setEnabled(0);
            upd.setNextFireTime(null);
            upd.setUpdatedAt(LocalDateTime.now());
            cronJobMapper.updateById(upd);
            log.info("CronJob disabled due to workstation change: id={}, name={}, reason={}", job.getId(), job.getJobName(), reason);
        }
    }

    public List<String> previewSchedule(String scheduleType, String cronExpr, Long intervalMs,
                                         LocalDateTime runAt, String timezone, Integer limit) {
        cronExpressionHelper.validateSchedule(scheduleType, cronExpr, intervalMs, runAt, timezone);
        int size = limit == null || limit <= 0 ? 5 : Math.min(limit, 10);
        return cronExpressionHelper.previewNextFireTimes(scheduleType, cronExpr, intervalMs, runAt, timezone, size);
    }

    public LinkworkCronJob getOwnedJob(Long id, String creatorId) {
        LinkworkCronJob job = cronJobMapper.selectById(id);
        if (job == null || (job.getIsDeleted() != null && job.getIsDeleted() == 1)) {
            throw new ResourceNotFoundException("CronJob not found: " + id);
        }
        if (!Objects.equals(job.getCreatorId(), creatorId)) {
            throw new ForbiddenOperationException("No permission to operate this cron job");
        }
        return job;
    }

    public CronJobResponse toResponse(LinkworkCronJob job, boolean includePreview) {
        CronJobResponse r = new CronJobResponse();
        r.setId(job.getId());
        r.setJobName(job.getJobName());
        r.setCreatorId(job.getCreatorId());
        r.setCreatorName(job.getCreatorName());
        r.setWorkstationId(job.getWorkstationId());
        r.setWorkstationName(job.getWorkstationName());
        r.setModelId(job.getModelId());
        r.setScheduleType(job.getScheduleType());
        r.setCronExpr(job.getCronExpr());
        r.setIntervalMs(job.getIntervalMs());
        r.setRunAt(job.getRunAt());
        r.setTimezone(job.getTimezone());
        r.setTaskContent(job.getTaskContent());
        r.setEnabled(job.getEnabled() != null && job.getEnabled() == 1);
        r.setDeleteAfterRun(job.getDeleteAfterRun() != null && job.getDeleteAfterRun() == 1);
        r.setMaxRetry(job.getMaxRetry());
        r.setConsecutiveFailures(job.getConsecutiveFailures());
        r.setNextFireTime(job.getNextFireTime());
        r.setNotifyMode(job.getNotifyMode());
        r.setNotifyTarget(job.getNotifyTarget());
        r.setTotalRuns(job.getTotalRuns());
        r.setLastRunTime(job.getLastRunTime());
        r.setLastRunStatus(job.getLastRunStatus());
        r.setCreatedAt(job.getCreatedAt());
        r.setUpdatedAt(job.getUpdatedAt());
        return r;
    }

    public CronJobRunResponse toRunResponse(LinkworkCronJobRun run) {
        CronJobRunResponse r = new CronJobRunResponse();
        r.setId(run.getId());
        r.setCronJobId(run.getCronJobId());
        r.setTaskNo(run.getTaskNo());
        r.setCreatorId(run.getCreatorId());
        r.setWorkstationId(run.getWorkstationId());
        r.setStatus(run.getStatus());
        r.setTriggerType(run.getTriggerType());
        r.setPlannedFireTime(run.getPlannedFireTime());
        r.setStartedAt(run.getStartedAt());
        r.setFinishedAt(run.getFinishedAt());
        r.setDurationMs(run.getDurationMs());
        r.setErrorMessage(run.getErrorMessage());
        r.setCreatedAt(run.getCreatedAt());
        return r;
    }

    private void enforceQuota(String creatorId, Long wsId) {
        long userCount = cronJobMapper.selectCount(new LambdaQueryWrapper<LinkworkCronJob>()
                .eq(LinkworkCronJob::getIsDeleted, 0).eq(LinkworkCronJob::getCreatorId, creatorId));
        if (userCount >= cronConfig.getMaxJobsPerUser()) {
            throw new IllegalArgumentException("Exceeded per-user cron job limit: " + cronConfig.getMaxJobsPerUser());
        }
        long wsCount = cronJobMapper.selectCount(new LambdaQueryWrapper<LinkworkCronJob>()
                .eq(LinkworkCronJob::getIsDeleted, 0).eq(LinkworkCronJob::getWorkstationId, wsId));
        if (wsCount >= cronConfig.getMaxJobsPerWorkstation()) {
            throw new IllegalArgumentException("Exceeded per-workstation cron job limit: " + cronConfig.getMaxJobsPerWorkstation());
        }
    }

    private void validateWorkstationVisible(Long wsId, String creatorId) {
        WorkstationEntity ws = workstationMapper.selectById(wsId);
        if (ws == null || Boolean.TRUE.equals(ws.getIsDeleted())) {
            throw new IllegalArgumentException("Workstation not found: " + wsId);
        }
        if (!"active".equalsIgnoreCase(ws.getStatus())) {
            throw new IllegalArgumentException("Workstation not active: " + ws.getStatus());
        }
        boolean visible = Boolean.TRUE.equals(ws.getIsPublic()) || Objects.equals(ws.getCreatorId(), creatorId);
        if (!visible) throw new ForbiddenOperationException("No permission to access this workstation");
    }

    private int normalizeMaxRetry(Integer maxRetry) {
        if (maxRetry == null) return 3;
        if (maxRetry < 1 || maxRetry > 20) throw new IllegalArgumentException("maxRetry range: 1~20");
        return maxRetry;
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "ABORTED".equals(status) || "SKIPPED".equals(status);
    }

    private void trimRunHistory(Long cronJobId) {
        int max = cronConfig.getMaxRunsPerJob();
        if (max <= 0) return;
        List<LinkworkCronJobRun> runs = cronJobRunMapper.selectList(new LambdaQueryWrapper<LinkworkCronJobRun>()
                .eq(LinkworkCronJobRun::getCronJobId, cronJobId).orderByDesc(LinkworkCronJobRun::getCreatedAt));
        if (runs.size() <= max) return;
        runs.stream().skip(max).map(LinkworkCronJobRun::getId).forEach(cronJobRunMapper::deleteById);
    }
}
