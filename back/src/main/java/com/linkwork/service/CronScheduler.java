package com.linkwork.service;

import com.linkwork.config.CronConfig;
import com.linkwork.model.entity.LinkworkCronJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CronScheduler {

    private final CronConfig cronConfig;
    private final DistributedLockService distributedLockService;
    private final CronJobV1Service cronJobService;
    private final CronJobExecutor cronJobExecutor;

    @Scheduled(fixedDelayString = "${linkwork.cron.scan-interval-ms:60000}")
    public void scanAndDispatch() {
        if (!cronConfig.isEnabled()) return;

        String lockKey = cronConfig.getLockKey();
        String lockValue = distributedLockService.tryAcquireLockByKey(lockKey, cronConfig.getLockTtlSeconds(), 1);
        if (lockValue == null) return;

        try {
            LocalDateTime threshold = LocalDateTime.now().plusNanos(cronConfig.getDispatchLeadMs() * 1_000_000);
            List<LinkworkCronJob> dueJobs = cronJobService.findDueJobs(threshold);
            if (dueJobs.isEmpty()) return;

            for (LinkworkCronJob job : dueJobs) {
                LocalDateTime firedAt = job.getNextFireTime();
                try {
                    cronJobExecutor.dispatchScheduled(job, firedAt);
                    cronJobService.advanceAfterDispatch(job, firedAt);
                } catch (Exception e) {
                    log.error("CronJob dispatch failed: id={}, err={}", job.getId(), e.getMessage(), e);
                    cronJobService.recordDispatchFailure(job, e.getMessage());
                }
            }
        } finally {
            distributedLockService.releaseLockByKey(lockKey, lockValue);
        }
    }
}
