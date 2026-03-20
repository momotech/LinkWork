package com.linkwork.service;

import com.linkwork.mapper.CronJobRunMapper;
import com.linkwork.model.dto.TaskCreateRequest;
import com.linkwork.model.entity.CronJob;
import com.linkwork.model.entity.CronJobRun;
import com.linkwork.model.entity.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class CronJobExecutor {

    private final CronJobRunMapper cronJobRunMapper;
    private final TaskService taskService;

    @Transactional
    public CronJobRun dispatchScheduled(CronJob cronJob, LocalDateTime plannedFireTime) {
        CronJobRun run = initRun(cronJob, "SCHEDULED", plannedFireTime);
        cronJobRunMapper.insert(run);

        try {
            Task task = taskService.createTask(
                    buildTaskCreateRequest(cronJob),
                    cronJob.getCreatorId(),
                    cronJob.getCreatorName(),
                    "cron-scheduler",
                    false,
                    "CRON",
                    cronJob.getId());

            CronJobRun update = new CronJobRun();
            update.setId(run.getId());
            update.setTaskNo(task.getTaskNo());
            update.setStatus("DISPATCHED");
            update.setStartedAt(LocalDateTime.now());
            cronJobRunMapper.updateById(update);

            run.setTaskNo(task.getTaskNo());
            run.setStatus("DISPATCHED");
            run.setStartedAt(update.getStartedAt());
            log.info("Cron 调度触发成功: cronJobId={}, runId={}, taskNo={}", cronJob.getId(), run.getId(), task.getTaskNo());
            return run;
        } catch (Exception e) {
            CronJobRun fail = new CronJobRun();
            fail.setId(run.getId());
            fail.setStatus("FAILED");
            fail.setFinishedAt(LocalDateTime.now());
            fail.setErrorMessage(e.getMessage());
            cronJobRunMapper.updateById(fail);
            throw e;
        }
    }

    @Transactional
    public CronJobRun dispatchManual(CronJob cronJob) {
        LocalDateTime now = LocalDateTime.now();
        CronJobRun run = initRun(cronJob, "MANUAL", now);
        cronJobRunMapper.insert(run);

        try {
            Task task = taskService.createTask(
                    buildTaskCreateRequest(cronJob),
                    cronJob.getCreatorId(),
                    cronJob.getCreatorName(),
                    "cron-manual-trigger",
                    false,
                    "CRON",
                    cronJob.getId());

            CronJobRun update = new CronJobRun();
            update.setId(run.getId());
            update.setTaskNo(task.getTaskNo());
            update.setStatus("DISPATCHED");
            update.setStartedAt(LocalDateTime.now());
            cronJobRunMapper.updateById(update);

            run.setTaskNo(task.getTaskNo());
            run.setStatus("DISPATCHED");
            run.setStartedAt(update.getStartedAt());
            log.info("Cron 手动触发成功: cronJobId={}, runId={}, taskNo={}", cronJob.getId(), run.getId(), task.getTaskNo());
            return run;
        } catch (Exception e) {
            CronJobRun fail = new CronJobRun();
            fail.setId(run.getId());
            fail.setStatus("FAILED");
            fail.setFinishedAt(LocalDateTime.now());
            fail.setErrorMessage(e.getMessage());
            cronJobRunMapper.updateById(fail);
            throw e;
        }
    }

    private CronJobRun initRun(CronJob cronJob, String triggerType, LocalDateTime plannedFireTime) {
        CronJobRun run = new CronJobRun();
        run.setCronJobId(cronJob.getId());
        run.setCreatorId(cronJob.getCreatorId());
        run.setRoleId(cronJob.getRoleId());
        run.setStatus("PENDING");
        run.setTriggerType(triggerType);
        run.setPlannedFireTime(plannedFireTime);
        run.setCreatedAt(LocalDateTime.now());
        return run;
    }

    private TaskCreateRequest buildTaskCreateRequest(CronJob cronJob) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setRoleId(cronJob.getRoleId());
        request.setModelId(cronJob.getModelId());
        request.setPrompt(cronJob.getTaskContent());
        request.setFileIds(null);
        return request;
    }
}
