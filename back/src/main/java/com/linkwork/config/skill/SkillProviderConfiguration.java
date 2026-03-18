package com.linkwork.config.skill;

import com.linkwork.agent.skill.core.SkillClient;
import com.linkwork.agent.skill.core.SkillException;
import com.linkwork.agent.skill.core.SkillProvider;
import com.linkwork.agent.skill.core.UnsupportedSkillProvider;
import com.linkwork.agent.skill.provider.gitlab.GitLabProviderImpl;
import com.linkwork.service.skill.provider.github.GitHubSkillProvider;
import com.linkwork.service.skill.provider.routing.RoutingSkillProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@EnableConfigurationProperties({SkillProviderRoutingProperties.class, GitHubProperties.class, LinkworkSkillProperties.class})
public class SkillProviderConfiguration {

    @Bean("linkworkGithubRestClient")
    @ConditionalOnProperty(prefix = "linkwork.skill.providers", name = "github-enabled", havingValue = "true", matchIfMissing = true)
    public RestClient linkworkGithubRestClient(GitHubProperties properties,
                                               ObjectProvider<RestClient.Builder> builderProvider) {
        RestClient.Builder builder = builderProvider.getIfAvailable(RestClient::builder)
                .baseUrl(properties.getApiUrl());
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + properties.getToken());
        }
        builder.defaultHeader("Accept", "application/vnd.github+json");
        return builder.build();
    }

    @Bean("githubSkillProvider")
    @ConditionalOnProperty(prefix = "linkwork.skill.providers", name = "github-enabled", havingValue = "true", matchIfMissing = true)
    public SkillProvider githubSkillProvider(@Qualifier("linkworkGithubRestClient") RestClient restClient,
                                             GitHubProperties properties) {
        return new GitHubSkillProvider(restClient, properties);
    }

    @Bean("linkworkGitlabRestClient")
    @ConditionalOnProperty(prefix = "linkwork.skill.providers", name = "gitlab-enabled", havingValue = "true")
    public RestClient linkworkGitlabRestClient(LinkworkSkillProperties properties,
                                               ObjectProvider<RestClient.Builder> builderProvider) {
        String baseUrl = properties.getGitlab().effectiveUrl();
        String token = properties.getGitlab().effectiveToken();
        RestClient.Builder builder = builderProvider.getIfAvailable(RestClient::builder);
        RestClient.Builder configured = builder.baseUrl(baseUrl);
        if (token != null && !token.isBlank()) {
            configured.defaultHeader("PRIVATE-TOKEN", token);
        }
        return configured.build();
    }

    @Bean("gitlabSkillProvider")
    @ConditionalOnProperty(prefix = "linkwork.skill.providers", name = "gitlab-enabled", havingValue = "true")
    public SkillProvider gitlabSkillProvider(@Qualifier("linkworkGitlabRestClient") RestClient restClient,
                                             LinkworkSkillProperties properties) {
        if (properties.getGitlab().effectiveToken() == null || properties.getGitlab().effectiveToken().isBlank()) {
            throw new SkillException("linkwork.skill.gitlab.token or deploy-token is required when gitlab-enabled=true");
        }
        if (properties.getGitlab().getProjectId() == null || properties.getGitlab().getProjectId().isBlank()) {
            throw new SkillException("linkwork.skill.gitlab.project-id is required when gitlab-enabled=true");
        }
        return new GitLabProviderImpl(restClient, properties.getGitlab());
    }

    @Bean
    @Primary
    public SkillProvider skillProvider(@Qualifier("githubSkillProvider") ObjectProvider<SkillProvider> githubProvider,
                                       @Qualifier("gitlabSkillProvider") ObjectProvider<SkillProvider> gitlabProvider,
                                       SkillProviderRoutingProperties routingProperties) {
        Map<String, SkillProvider> providers = new LinkedHashMap<>();
        SkillProvider github = githubProvider.getIfAvailable();
        SkillProvider gitlab = gitlabProvider.getIfAvailable();
        if (github != null) {
            providers.put("github", github);
        }
        if (gitlab != null) {
            providers.put("gitlab", gitlab);
        }
        if (providers.isEmpty()) {
            return new UnsupportedSkillProvider("none");
        }
        return new RoutingSkillProvider(providers, routingProperties.getDefaultProvider());
    }

    @Bean
    @Primary
    public SkillClient skillClient(SkillProvider skillProvider, LinkworkSkillProperties properties) {
        return new SkillClient(
                skillProvider,
                properties.getRetryTimes(),
                Duration.ofMillis(properties.getRetryBackoffMs()),
                Duration.ofMillis(properties.getCacheTtlMs())
        );
    }
}
