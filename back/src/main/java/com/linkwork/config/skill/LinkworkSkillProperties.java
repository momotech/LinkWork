package com.linkwork.config.skill;

import com.linkwork.agent.skill.provider.gitlab.GitLabProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "linkwork.skill")
public class LinkworkSkillProperties {
    private boolean enabled = true;
    private String provider = "multi";
    private int retryTimes = 2;
    private long retryBackoffMs = 200;
    private long cacheTtlMs = 10000;
    private GitLabProperties gitlab = new GitLabProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getRetryTimes() {
        return retryTimes;
    }

    public void setRetryTimes(int retryTimes) {
        this.retryTimes = retryTimes;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public long getCacheTtlMs() {
        return cacheTtlMs;
    }

    public void setCacheTtlMs(long cacheTtlMs) {
        this.cacheTtlMs = cacheTtlMs;
    }

    public GitLabProperties getGitlab() {
        return gitlab;
    }

    public void setGitlab(GitLabProperties gitlab) {
        this.gitlab = gitlab;
    }
}
