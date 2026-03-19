package com.linkwork.service;

import com.linkwork.mapper.CronJobRunMapper;
import com.linkwork.model.dto.TaskCreateRequest;
import com.linkwork.model.entity.LinkworkCronJob;
import com.linkwork.model.entity.LinkworkCronJobRun;
import com.linkwork.model.entity.LinkworkTask;
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
    private final TaskV1Service taskService;

    @Transactional
    public LinkworkCronJobRun dispatchScheduled(LinkworkCronJob job, LocalDateTime plannedFireTime) {
        LinkworkCronJobRun run = initRun(job, "SCHEDULED", plannedFireTime);
        cronJobRunMapper.insert(run);
        try {
            LinkworkTask task = taskService.createTask(
                    buildRequest(job), job.getCreatorId(), job.getCreatorName(),
                    "cron-scheduler", "CRON", job.getId());
            updateRunDispatched(run, task.getTaskNo());
            log.info("CronJob scheduled dispatch: cronJobId={}, runId={}, taskNo={}", job.getId(), run.getId(), task.getTaskNo());
            return run;
        } catch (Exception e) {
            markRunFailed(run, e.getMessage());
            throw e;
        }
    }

    @Transactional
    public LinkworkCronJobRun dispatchManual(LinkworkCronJob job) {
        LinkworkCronJobRun run = initRun(job, "MANUAL", LocalDateTime.now());
        cronJobRunMapper.insert(run);
        try {
            LinkworkTask task = taskService.createTask(
                    buildRequest(job), job.getCreatorId(), job.getCreatorName(),
                    "cron-manual", "CRON", job.getId());
            updateRunDispatched(run, task.getTaskNo());
            log.info("CronJob manual dispatch: cronJobId={}, runId={}, taskNo={}", job.getId(), run.getId(), task.getTaskNo());
            return run;
        } catch (Exception e) {
            markRunFailed(run, e.getMessage());
            throw e;
        }
    }

    private LinkworkCronJobRun initRun(LinkworkCronJob job, String triggerType, LocalDateTime plannedFireTime) {
        LinkworkCronJobRun run = new LinkworkCronJobRun();
        run.setCronJobId(job.getId());
        run.setCreatorId(job.getCreatorId());
        run.setWorkstationId(job.getWorkstationId());
        run.setStatus("PENDING");
        run.setTriggerType(triggerType);
        run.setPlannedFireTime(plannedFireTime);
        run.setCreatedAt(LocalDateTime.now());
        return run;
    }

    private void updateRunDispatched(LinkworkCronJobRun run, String taskNo) {
        LinkworkCronJobRun upd = new LinkworkCronJobRun();
        upd.setId(run.getId());
        upd.setTaskNo(taskNo);
        upd.setStatus("DISPATCHED");
        upd.setStartedAt(LocalDateTime.now());
        cronJobRunMapper.updateById(upd);
        run.setTaskNo(taskNo);
        run.setStatus("DISPATCHED");
    }

    private void markRunFailed(LinkworkCronJobRun run, String error) {
        LinkworkCronJobRun upd = new LinkworkCronJobRun();
        upd.setId(run.getId());
        upd.setStatus("FAILED");
        upd.setFinishedAt(LocalDateTime.now());
        upd.setErrorMessage(error);
        cronJobRunMapper.updateById(upd);
    }

    private TaskCreateRequest buildRequest(LinkworkCronJob job) {
        TaskCreateRequest req = new TaskCreateRequest();
        req.setWorkstationId(job.getWorkstationId());
        req.setSelectedModel(job.getModelId());
        req.setPrompt(job.getTaskContent());
        return req;
    }
}
