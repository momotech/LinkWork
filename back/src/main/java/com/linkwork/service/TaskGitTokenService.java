package com.linkwork.service;

import com.linkwork.common.ForbiddenOperationException;
import com.linkwork.common.ResourceNotFoundException;
import com.linkwork.model.dto.TaskGitTokenResponse;
import com.linkwork.model.entity.TaskGitAuthEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 任务运行期 Git token 获取服务（供 zzd 调用）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskGitTokenService {

    private static final Duration REFRESH_AHEAD_WINDOW = Duration.ofMinutes(5);
    private static final String SCOPE_READ_REPOSITORY = "read_repository";
    private static final String SCOPE_WRITE_REPOSITORY = "write_repository";
    private static final String SCOPE_API = "api";

    private final TaskService taskService;
    private final TaskGitAuthService taskGitAuthService;
    private final GitLabAuthService gitLabAuthService;

    public TaskGitTokenResponse getTaskGitToken(String taskId) {
        ensureTaskExists(taskId);

        TaskGitAuthEntity binding = taskGitAuthService.getByTaskId(taskId);
        if (binding == null || binding.getGitlabAuthId() == null) {
            throw new ResourceNotFoundException("任务未绑定 Git 认证: taskId=" + taskId);
        }

        if (!TaskGitAuthService.PROVIDER_GITLAB.equalsIgnoreCase(binding.getProvider())) {
            throw new ForbiddenOperationException("仅支持 gitlab provider，当前 provider=" + binding.getProvider());
        }

        GitLabAuthService.ValidToken token =
                gitLabAuthService.getValidTokenByAuthId(binding.getGitlabAuthId(), REFRESH_AHEAD_WINDOW);
        if (token == null) {
            throw new ResourceNotFoundException("任务未绑定有效 Git 认证: taskId=" + taskId);
        }

        if (!StringUtils.hasText(token.getToken())) {
            throw new IllegalStateException("任务 Git token 不可用: taskId=" + taskId);
        }

        ensureScopeSufficient(token.getScope(), taskId);
        GitLabAuthService.CommitIdentity commitIdentity = gitLabAuthService.resolveCommitIdentity(token.getToken());

        taskGitAuthService.updateExpiresAt(taskId, token.getExpiresAt());
        log.info("任务 Git token 发放: taskId={}, provider={}, gitlabAuthId={}, tokenType={}, scope={}, tokenAlias={}, commitUser={}",
                taskId,
                binding.getProvider(),
                binding.getGitlabAuthId(),
                token.getTokenType(),
                token.getScope(),
                token.getTokenAlias(),
                commitIdentity.getUsername());

        TaskGitTokenResponse response = new TaskGitTokenResponse();
        response.setProvider(TaskGitAuthService.PROVIDER_GITLAB);
        response.setTokenType(token.getTokenType());
        response.setToken(token.getToken());
        response.setExpiresAt(DateTimeFormatter.ISO_INSTANT.format(token.getExpiresAt().atOffset(ZoneOffset.UTC).toInstant()));
        response.setCommitUserName(commitIdentity.getUsername());
        response.setCommitUserEmail(commitIdentity.getEmail());
        return response;
    }

    /**
     * Scope fail-fast：发放前校验 scope 是否满足仓库访问要求。
     * 至少包含 read_repository / write_repository / api 之一。
     * GitLab 中 write_repository 隐含读能力，api 隐含仓库读写权限。
     * 旧授权若 scope 不足，直接拒绝并引导重新授权，避免任务在 GIT_PRE 阶段才失败。
     */
    private void ensureScopeSufficient(String scope, String taskId) {
        if (!StringUtils.hasText(scope)) {
            log.warn("Git token scope 为空，跳过校验: taskId={}", taskId);
            return;
        }
        boolean hasRepoAccess = scope.contains(SCOPE_READ_REPOSITORY)
                || scope.contains(SCOPE_WRITE_REPOSITORY)
                || scope.contains(SCOPE_API);
        if (!hasRepoAccess) {
            log.warn("Git token scope 不满足仓库访问要求: taskId={}, scope={}", taskId, scope);
            throw new ForbiddenOperationException(
                    "Git 授权 scope 不足（缺少 read_repository、write_repository 或 api），请删除旧授权后重新授权: taskId=" + taskId);
        }
    }

    private void ensureTaskExists(String taskId) {
        try {
            taskService.getTaskByNo(taskId);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("任务不存在: " + taskId);
        }
    }
}
