package com.linkwork.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("linkwork_cron_job_run")
public class CronJobRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long cronJobId;
    private String taskNo;
    private String creatorId;
    private Long roleId;
    private String status;
    private String triggerType;
    private LocalDateTime plannedFireTime;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private String errorMessage;
    private LocalDateTime createdAt;
}
