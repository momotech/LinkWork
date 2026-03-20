package com.linkwork.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "robot.cron")
public class CronConfig {
    private boolean enabled = true;
    private long scanIntervalMs = 60_000;
    private long dispatchLeadMs = 180_000;
    private int maxJobsPerUser = 50;
    private int maxJobsPerRole = 100;
    private int maxRunsPerJob = 100;
    private String lockKey = "lock:cron:scanner";
    private int lockTtlSeconds = 55;
}
