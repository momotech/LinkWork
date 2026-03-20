package com.linkwork.service;

import com.linkwork.mapper.TaskGitAuthMapper;
import com.linkwork.model.entity.GitLabAuthEntity;
import com.linkwork.model.entity.TaskGitAuthEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 任务与 Git 认证映射服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskGitAuthService {

    public static final String PROVIDER_GITLAB = "gitlab";

    private final TaskGitAuthMapper taskGitAuthMapper;
    private final GitLabAuthService gitLabAuthService;

    /**
     * 在任务创建时绑定最新的 GitLab 认证记录。
     */
    public void bindTaskWithLatestGitAuth(String taskId, String userId) {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(userId)) {
            return;
        }

        GitLabAuthEntity latestAuth = gitLabAuthService.getLatestAuth(userId);
        if (latestAuth == null) {
            log.info("任务未绑定 Git 认证（用户暂无授权）: taskId={}, userId={}", taskId, userId);
            return;
        }

        TaskGitAuthEntity entity = new TaskGitAuthEntity();
        entity.setTaskId(taskId);
        entity.setUserId(userId);
        entity.setProvider(PROVIDER_GITLAB);
        entity.setGitlabAuthId(latestAuth.getId());
        entity.setExpiresAt(latestAuth.getExpiresAt());
        entity.setUpdatedAt(LocalDateTime.now());
        entity.setIsDeleted(false);

        TaskGitAuthEntity existing = taskGitAuthMapper.selectById(taskId);
        if (existing == null) {
            entity.setCreatedAt(LocalDateTime.now());
            taskGitAuthMapper.insert(entity);
        } else {
            entity.setCreatedAt(existing.getCreatedAt());
            taskGitAuthMapper.updateById(entity);
        }

        log.info("任务绑定 Git 认证成功: taskId={}, userId={}, provider={}, gitlabAuthId={}",
                taskId, userId, PROVIDER_GITLAB, latestAuth.getId());
    }

    public TaskGitAuthEntity getByTaskId(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return null;
        }
        return taskGitAuthMapper.selectById(taskId);
    }

    public void updateExpiresAt(String taskId, LocalDateTime expiresAt) {
        if (!StringUtils.hasText(taskId) || expiresAt == null) {
            return;
        }
        TaskGitAuthEntity entity = taskGitAuthMapper.selectById(taskId);
        if (entity == null) {
            return;
        }
        entity.setExpiresAt(expiresAt);
        entity.setUpdatedAt(LocalDateTime.now());
        taskGitAuthMapper.updateById(entity);
    }
}
