package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkwork.common.ForbiddenOperationException;
import com.linkwork.common.ResourceNotFoundException;
import com.linkwork.config.CronConfig;
import com.linkwork.mapper.CronJobMapper;
import com.linkwork.mapper.CronJobRunMapper;
import com.linkwork.mapper.RoleMapper;
import com.linkwork.model.dto.CronJobCreateRequest;
import com.linkwork.model.dto.CronJobResponse;
import com.linkwork.model.dto.CronJobRunResponse;
import com.linkwork.model.dto.CronJobToggleRequest;
import com.linkwork.model.dto.CronJobUpdateRequest;
import com.linkwork.model.entity.CronJob;
import com.linkwork.model.entity.CronJobRun;
import com.linkwork.model.entity.RoleEntity;
import com.linkwork.model.entity.Task;
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
public class CronJobService {

    private static final String NOTIFY_NONE = "none";

    private final CronJobMapper cronJobMapper;
    private final CronJobRunMapper cronJobRunMapper;
    private final RoleMapper roleMapper;
    private final CronExpressionHelper cronExpressionHelper;
    private final CronConfig cronConfig;

    @Transactional
    public CronJobResponse create(CronJobCreateRequest request, String creatorId, String creatorName) {
        validateRoleVisible(request.getRoleId(), creatorId);
        validateSchedule(request.getScheduleType(), request.getCronExpr(), request.getIntervalMs(),
                request.getRunAt(), request.getTimezone());
        enforceQuota(creatorId, request.getRoleId());

        RoleEntity role = roleMapper.selectById(request.getRoleId());
        CronJob job = new CronJob();
        job.setJobName(request.getJobName().trim());
        job.setCreatorId(creatorId);
        job.setCreatorName(StringUtils.hasText(creatorName) ? creatorName : creatorId);
        job.setRoleId(request.getRoleId());
        job.setRoleName(role.getName());
        job.setModelId(request.getModelId().trim());
        job.setFileIdsJson(null);
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
        // Cron 通知策略临时下线：固定兼容值，实际结果通知由任务终态链路统一处理。
        job.setNotifyMode(NOTIFY_NONE);
        job.setNotifyTarget(null);
        job.setNextFireTime(cronExpressionHelper.computeFirstFireTime(
                job.getScheduleType(), job.getCronExpr(), job.getIntervalMs(), job.getRunAt(), job.getTimezone()));
        job.setTotalRuns(0);
        job.setIsDeleted(0);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());

        cronJobMapper.insert(job);
        return toResponse(job, true);
    }

    public Map<String, Object> listMine(String creatorId, Long roleId, Boolean enabled, String scheduleType,
                                        String keyword, Integer page, Integer pageSize) {
        int pageNum = page == null || page <= 0 ? 1 : page;
        int size = pageSize == null || pageSize <= 0 ? 20 : pageSize;

        Page<CronJob> pageReq = new Page<>(pageNum, size);
        LambdaQueryWrapper<CronJob> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CronJob::getIsDeleted, 0)
                .eq(CronJob::getCreatorId, creatorId)
                .orderByDesc(CronJob::getCreatedAt);
        if (roleId != null) {
            wrapper.eq(CronJob::getRoleId, roleId);
        }
        if (enabled != null) {
            wrapper.eq(CronJob::getEnabled, enabled ? 1 : 0);
        }
        if (StringUtils.hasText(scheduleType)) {
            wrapper.eq(CronJob::getScheduleType, cronExpressionHelper.normalizeScheduleType(scheduleType));
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.like(CronJob::getJobName, keyword.trim());
        }

        Page<CronJob> result = cronJobMapper.selectPage(pageReq, wrapper);
        List<CronJobResponse> items = result.getRecords().stream().map(job -> toResponse(job, false)).toList();

        Map<String, Object> pagination = new HashMap<>();
        pagination.put("page", result.getCurrent());
        pagination.put("pageSize", result.getSize());
        pagination.put("total", result.getTotal());
        pagination.put("totalPages", result.getPages());

        Map<String, Object> data = new HashMap<>();
        data.put("items", items);
        data.put("pagination", pagination);
        return data;
    }

    public CronJobResponse getDetail(Long id, String creatorId) {
        CronJob job = getOwnedJob(id, creatorId);
        return toResponse(job, true);
    }

    @Transactional
    public CronJobResponse update(Long id, CronJobUpdateRequest request, String creatorId) {
        CronJob job = getOwnedJob(id, creatorId);

        validateRoleVisible(request.getRoleId(), creatorId);
        validateSchedule(request.getScheduleType(), request.getCronExpr(), request.getIntervalMs(),
                request.getRunAt(), request.getTimezone());

        RoleEntity role = roleMapper.selectById(request.getRoleId());
        job.setJobName(request.getJobName().trim());
        job.setRoleId(request.getRoleId());
        job.setRoleName(role.getName());
        job.setModelId(request.getModelId().trim());
        job.setFileIdsJson(null);
        job.setScheduleType(cronExpressionHelper.normalizeScheduleType(request.getScheduleType()));
        job.setCronExpr(StringUtils.hasText(request.getCronExpr()) ? request.getCronExpr().trim() : null);
        job.setIntervalMs(request.getIntervalMs());
        job.setRunAt(request.getRunAt());
        job.setTimezone(cronExpressionHelper.normalizeTimezone(request.getTimezone()));
        job.setTaskContent(request.getTaskContent().trim());
        job.setDeleteAfterRun(Boolean.TRUE.equals(request.getDeleteAfterRun()) ? 1 : 0);
        if ("at".equals(job.getScheduleType()) && request.getDeleteAfterRun() == null) {
            job.setDeleteAfterRun(1);
        }
        job.setMaxRetry(normalizeMaxRetry(request.getMaxRetry()));
        // Cron 通知策略临时下线：固定兼容值，实际结果通知由任务终态链路统一处理。
        job.setNotifyMode(NOTIFY_NONE);
        job.setNotifyTarget(null);
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
        CronJob job = getOwnedJob(id, creatorId);
        boolean enabled = Boolean.TRUE.equals(request.getEnabled());
        job.setEnabled(enabled ? 1 : 0);
        if (enabled) {
            cronExpressionHelper.validateSchedule(job.getScheduleType(), job.getCronExpr(), job.getIntervalMs(),
                    job.getRunAt(), job.getTimezone());
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
        CronJob job = getOwnedJob(id, creatorId);
        cronJobMapper.deleteById(job.getId());
    }

    public Map<String, Object> listRuns(Long cronJobId, String creatorId, Integer page, Integer pageSize) {
        getOwnedJob(cronJobId, creatorId);
        int pageNum = page == null || page <= 0 ? 1 : page;
        int size = pageSize == null || pageSize <= 0 ? 20 : pageSize;
        Page<CronJobRun> req = new Page<>(pageNum, size);
        LambdaQueryWrapper<CronJobRun> wrapper = new LambdaQueryWrapper<CronJobRun>()
                .eq(CronJobRun::getCronJobId, cronJobId)
                .orderByDesc(CronJobRun::getCreatedAt);
        Page<CronJobRun> result = cronJobRunMapper.selectPage(req, wrapper);

        List<CronJobRunResponse> items = result.getRecords().stream().map(this::toRunResponse).toList();
        Map<String, Object> pagination = new HashMap<>();
        pagination.put("page", result.getCurrent());
        pagination.put("pageSize", result.getSize());
        pagination.put("total", result.getTotal());
        pagination.put("totalPages", result.getPages());

        Map<String, Object> data = new HashMap<>();
        data.put("items", items);
        data.put("pagination", pagination);
        return data;
    }

    public List<CronJob> findDueJobs(LocalDateTime threshold) {
        return cronJobMapper.selectList(new LambdaQueryWrapper<CronJob>()
                .eq(CronJob::getEnabled, 1)
                .eq(CronJob::getIsDeleted, 0)
                .isNotNull(CronJob::getNextFireTime)
                .le(CronJob::getNextFireTime, threshold)
                .orderByAsc(CronJob::getNextFireTime));
    }

    @Transactional
    public void advanceAfterDispatch(CronJob job, LocalDateTime firedAt) {
        CronJob update = new CronJob();
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
    public void recordDispatchFailure(CronJob job, String error) {
        int failures = (job.getConsecutiveFailures() == null ? 0 : job.getConsecutiveFailures()) + 1;
        CronJob update = new CronJob();
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

        if (failures >= normalizeMaxRetry(job.getMaxRetry())) {
            log.info("Cron 连续失败已自动禁用: cronJobId={}, jobName={}, reason={}", job.getId(), job.getJobName(), truncate(error));
        }
    }

    @Transactional
    public void onTaskStatusChanged(Task task, TaskStatus status) {
        if (task == null || !"CRON".equalsIgnoreCase(task.getSource()) || task.getCronJobId() == null) {
            return;
        }

        CronJobRun run = cronJobRunMapper.selectOne(new LambdaQueryWrapper<CronJobRun>()
                .eq(CronJobRun::getTaskNo, task.getTaskNo())
                .orderByDesc(CronJobRun::getId)
                .last("LIMIT 1"));
        if (run == null) {
            return;
        }

        String mappedStatus = switch (status) {
            case RUNNING -> "RUNNING";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case ABORTED -> "ABORTED";
            default -> null;
        };
        if (mappedStatus == null) {
            return;
        }

        if (isTerminal(run.getStatus())) {
            return;
        }

        CronJobRun update = new CronJobRun();
        update.setId(run.getId());
        update.setStatus(mappedStatus);
        if ("RUNNING".equals(mappedStatus)) {
            if (run.getStartedAt() == null) {
                update.setStartedAt(LocalDateTime.now());
            }
            cronJobRunMapper.updateById(update);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        update.setFinishedAt(now);
        if (task.getDurationMs() != null && task.getDurationMs() > 0) {
            update.setDurationMs(task.getDurationMs());
        } else if (run.getStartedAt() != null) {
            update.setDurationMs(Duration.between(run.getStartedAt(), now).toMillis());
        }
        if (status == TaskStatus.FAILED) {
            update.setErrorMessage(extractTaskError(task));
        }
        cronJobRunMapper.updateById(update);

        CronJob job = cronJobMapper.selectById(task.getCronJobId());
        if (job == null || job.getIsDeleted() != null && job.getIsDeleted() == 1) {
            return;
        }

        CronJob jobUpdate = new CronJob();
        jobUpdate.setId(job.getId());
        jobUpdate.setLastRunTime(now);
        jobUpdate.setLastRunStatus(mappedStatus);
        jobUpdate.setUpdatedAt(now);

        if (status == TaskStatus.COMPLETED) {
            jobUpdate.setConsecutiveFailures(0);
        } else if (status == TaskStatus.FAILED) {
            int failures = (job.getConsecutiveFailures() == null ? 0 : job.getConsecutiveFailures()) + 1;
            jobUpdate.setConsecutiveFailures(failures);
            if (failures >= normalizeMaxRetry(job.getMaxRetry())) {
                jobUpdate.setEnabled(0);
                jobUpdate.setNextFireTime(null);
            }
        }
        cronJobMapper.updateById(jobUpdate);

        log.debug("Cron 任务终态已同步: cronJobId={}, taskNo={}, status={}", job.getId(), task.getTaskNo(), mappedStatus);
    }

    @Transactional
    public void disableByRoleId(Long roleId, String reason) {
        if (roleId == null) {
            return;
        }
        List<CronJob> jobs = cronJobMapper.selectList(new LambdaQueryWrapper<CronJob>()
                .eq(CronJob::getRoleId, roleId)
                .eq(CronJob::getIsDeleted, 0)
                .eq(CronJob::getEnabled, 1));

        for (CronJob job : jobs) {
            CronJob update = new CronJob();
            update.setId(job.getId());
            update.setEnabled(0);
            update.setNextFireTime(null);
            update.setUpdatedAt(LocalDateTime.now());
            cronJobMapper.updateById(update);
            log.info("Cron 任务因岗位变更被禁用: cronJobId={}, jobName={}, reason={}", job.getId(), job.getJobName(), reason);
        }
    }

    public CronJob getOwnedJob(Long id, String creatorId) {
        CronJob job = cronJobMapper.selectById(id);
        if (job == null || (job.getIsDeleted() != null && job.getIsDeleted() == 1)) {
            throw new ResourceNotFoundException("定时任务不存在: id=" + id);
        }
        if (!Objects.equals(job.getCreatorId(), creatorId)) {
            throw new ForbiddenOperationException("无权限操作该定时任务");
        }
        return job;
    }

    public CronJobResponse toResponse(CronJob job, boolean includePreview) {
        CronJobResponse response = new CronJobResponse();
        response.setId(job.getId());
        response.setJobName(job.getJobName());
        response.setCreatorId(job.getCreatorId());
        response.setCreatorName(job.getCreatorName());
        response.setRoleId(job.getRoleId());
        response.setRoleName(job.getRoleName());
        response.setModelId(job.getModelId());
        response.setScheduleType(job.getScheduleType());
        response.setCronExpr(job.getCronExpr());
        response.setIntervalMs(job.getIntervalMs());
        response.setRunAt(job.getRunAt());
        response.setTimezone(job.getTimezone());
        response.setTaskContent(job.getTaskContent());
        response.setEnabled(job.getEnabled() != null && job.getEnabled() == 1);
        response.setDeleteAfterRun(job.getDeleteAfterRun() != null && job.getDeleteAfterRun() == 1);
        response.setMaxRetry(job.getMaxRetry());
        response.setConsecutiveFailures(job.getConsecutiveFailures());
        response.setNextFireTime(job.getNextFireTime());
        response.setNotifyMode(job.getNotifyMode());
        response.setNotifyTarget(job.getNotifyTarget());
        response.setTotalRuns(job.getTotalRuns());
        response.setLastRunTime(job.getLastRunTime());
        response.setLastRunStatus(job.getLastRunStatus());
        response.setCreatedAt(job.getCreatedAt());
        response.setUpdatedAt(job.getUpdatedAt());

        if (includePreview) {
            response.setNextFireTimes(cronExpressionHelper.previewNextFireTimes(
                    job.getScheduleType(), job.getCronExpr(), job.getIntervalMs(), job.getRunAt(), job.getTimezone(), 5));
        }
        return response;
    }

    public CronJobRunResponse toRunResponse(CronJobRun run) {
        CronJobRunResponse response = new CronJobRunResponse();
        response.setId(run.getId());
        response.setCronJobId(run.getCronJobId());
        response.setTaskNo(run.getTaskNo());
        response.setCreatorId(run.getCreatorId());
        response.setRoleId(run.getRoleId());
        response.setStatus(run.getStatus());
        response.setTriggerType(run.getTriggerType());
        response.setPlannedFireTime(run.getPlannedFireTime());
        response.setStartedAt(run.getStartedAt());
        response.setFinishedAt(run.getFinishedAt());
        response.setDurationMs(run.getDurationMs());
        response.setErrorMessage(run.getErrorMessage());
        response.setCreatedAt(run.getCreatedAt());
        return response;
    }

    public List<String> previewSchedule(String scheduleType, String cronExpr, Long intervalMs,
                                        LocalDateTime runAt, String timezone, Integer limit) {
        cronExpressionHelper.validateSchedule(scheduleType, cronExpr, intervalMs, runAt, timezone);
        int size = limit == null || limit <= 0 ? 5 : Math.min(limit, 10);
        return cronExpressionHelper.previewNextFireTimes(
                scheduleType, cronExpr, intervalMs, runAt, timezone, size);
    }

    private void enforceQuota(String creatorId, Long roleId) {
        long userCount = cronJobMapper.selectCount(new LambdaQueryWrapper<CronJob>()
                .eq(CronJob::getIsDeleted, 0)
                .eq(CronJob::getCreatorId, creatorId));
        if (userCount >= cronConfig.getMaxJobsPerUser()) {
            throw new IllegalArgumentException("已达到每用户定时任务上限: " + cronConfig.getMaxJobsPerUser());
        }

        long roleCount = cronJobMapper.selectCount(new LambdaQueryWrapper<CronJob>()
                .eq(CronJob::getIsDeleted, 0)
                .eq(CronJob::getRoleId, roleId));
        if (roleCount >= cronConfig.getMaxJobsPerRole()) {
            throw new IllegalArgumentException("已达到每岗位定时任务上限: " + cronConfig.getMaxJobsPerRole());
        }
    }

    private void validateRoleVisible(Long roleId, String creatorId) {
        RoleEntity role = roleMapper.selectById(roleId);
        if (role == null || Boolean.TRUE.equals(role.getIsDeleted())) {
            throw new IllegalArgumentException("岗位不存在: roleId=" + roleId);
        }
        if (!"active".equalsIgnoreCase(role.getStatus())) {
            throw new IllegalArgumentException("岗位不可用: status=" + role.getStatus());
        }
        boolean visible = Boolean.TRUE.equals(role.getIsPublic()) || Objects.equals(role.getCreatorId(), creatorId);
        if (!visible) {
            throw new ForbiddenOperationException("当前用户无权访问该岗位");
        }
    }

    private void validateSchedule(String scheduleType, String cronExpr, Long intervalMs,
                                  LocalDateTime runAt, String timezone) {
        cronExpressionHelper.validateSchedule(scheduleType, cronExpr, intervalMs, runAt, timezone);
    }

    private int normalizeMaxRetry(Integer maxRetry) {
        if (maxRetry == null) {
            return 3;
        }
        if (maxRetry < 1 || maxRetry > 20) {
            throw new IllegalArgumentException("maxRetry 取值范围为 1~20");
        }
        return maxRetry;
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "ABORTED".equals(status) || "SKIPPED".equals(status);
    }

    private void trimRunHistory(Long cronJobId) {
        int maxRuns = cronConfig.getMaxRunsPerJob();
        if (maxRuns <= 0) {
            return;
        }
        List<CronJobRun> runs = cronJobRunMapper.selectList(new LambdaQueryWrapper<CronJobRun>()
                .eq(CronJobRun::getCronJobId, cronJobId)
                .orderByDesc(CronJobRun::getCreatedAt));
        if (runs.size() <= maxRuns) {
            return;
        }
        List<Long> removeIds = runs.stream().skip(maxRuns).map(CronJobRun::getId).toList();
        for (Long id : removeIds) {
            cronJobRunMapper.deleteById(id);
        }
    }

    private String truncate(String text) {
        if (!StringUtils.hasText(text)) {
            return "unknown";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    private String extractTaskError(Task task) {
        if (task == null || !StringUtils.hasText(task.getReportJson())) {
            return "unknown";
        }
        return truncate(task.getReportJson());
    }
}
