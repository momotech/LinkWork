package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("linkwork_cron_job")
public class CronJob {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobName;
    private String creatorId;
    private String creatorName;
    private Long roleId;
    private String roleName;
    private String modelId;
    private String fileIdsJson;
    private String scheduleType;
    private String cronExpr;
    private Long intervalMs;
    private LocalDateTime runAt;
    private String timezone;
    private String taskContent;
    private Integer enabled;
    private Integer deleteAfterRun;
    private Integer maxRetry;
    private Integer consecutiveFailures;
    private LocalDateTime nextFireTime;
    private String notifyMode;
    private String notifyTarget;
    private Integer totalRuns;
    private LocalDateTime lastRunTime;
    private String lastRunStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer isDeleted;
}
