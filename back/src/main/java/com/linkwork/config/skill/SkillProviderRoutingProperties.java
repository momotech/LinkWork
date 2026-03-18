package com.linkwork.config.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "linkwork.skill.providers")
public class SkillProviderRoutingProperties {
    private String defaultProvider = "github";
    private boolean githubEnabled = true;
    private boolean gitlabEnabled = false;

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public boolean isGithubEnabled() {
        return githubEnabled;
    }

    public void setGithubEnabled(boolean githubEnabled) {
        this.githubEnabled = githubEnabled;
    }

    public boolean isGitlabEnabled() {
        return gitlabEnabled;
    }

    public void setGitlabEnabled(boolean gitlabEnabled) {
        this.gitlabEnabled = gitlabEnabled;
    }
}
