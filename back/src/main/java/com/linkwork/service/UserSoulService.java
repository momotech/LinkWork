package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linkwork.mapper.UserSoulMapper;
import com.linkwork.model.dto.UserSoulResponse;
import com.linkwork.model.dto.UserSoulUpsertRequest;
import com.linkwork.model.entity.UserSoulEntity;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserSoulService {

    private static final int MAX_CONTENT_LENGTH = 8000;
    private static final int MAX_PRESET_ID_LENGTH = 64;
    private static final int MAX_OPERATOR_NAME_LENGTH = 100;

    private final UserSoulMapper userSoulMapper;

    public UserSoulResponse getCurrentUserSoul(String userId) {
        UserSoulEntity entity = findByUserId(userId);
        if (entity == null) {
            throw new IllegalArgumentException("SOUL_NOT_FOUND: 当前用户尚未配置 Soul");
        }
        return toResponse(entity);
    }

    public String getRequiredSoulContent(String userId) {
        UserSoulEntity entity = findByUserId(userId);
        if (entity == null) {
            throw new IllegalArgumentException("SOUL_NOT_FOUND: 当前用户尚未配置 Soul");
        }
        String content = normalizeContent(entity.getContent());
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("SOUL_CONTENT_INVALID: Soul 内容不能为空");
        }
        return content;
    }

    public String getOptionalSoulContent(String userId) {
        UserSoulEntity entity = findByUserId(userId);
        if (entity == null) {
            return "";
        }
        return normalizeContent(entity.getContent());
    }

    @Transactional
    public UserSoulResponse upsertCurrentUserSoul(String userId, String userName, UserSoulUpsertRequest request) {
        String content = normalizeContent(request.getContent());
        validateContent(content);
        String operatorName = normalizeOperatorName(userName, userId);

        UserSoulEntity existing = findByUserId(userId);
        if (existing == null) {
            if (request.getVersion() != 0L) {
                throw new IllegalArgumentException("SOUL_VERSION_CONFLICT: 首次保存 version 必须为 0");
            }
            UserSoulEntity entity = new UserSoulEntity();
            entity.setUserId(userId);
            entity.setContent(content);
            entity.setPresetId(normalizePresetId(request.getPresetId()));
            entity.setVersion(1L);
            entity.setCreatorId(userId);
            entity.setCreatorName(operatorName);
            entity.setUpdaterId(userId);
            entity.setUpdaterName(operatorName);
            int inserted = userSoulMapper.insert(entity);
            if (inserted != 1) {
                throw new IllegalStateException("创建用户 Soul 失败: userId=" + userId);
            }
            return toResponse(entity);
        }

        Long storedVersion = existing.getVersion();
        Long currentVersion = normalizeVersion(storedVersion);
        if (!currentVersion.equals(request.getVersion())) {
            throw new IllegalArgumentException("SOUL_VERSION_CONFLICT: Soul 已被更新，请刷新后重试");
        }

        long nextVersion = currentVersion + 1;
        LambdaUpdateWrapper<UserSoulEntity> wrapper = new LambdaUpdateWrapper<UserSoulEntity>()
                .set(UserSoulEntity::getContent, content)
                .set(UserSoulEntity::getPresetId, normalizePresetId(request.getPresetId()))
                .set(UserSoulEntity::getUpdaterId, userId)
                .set(UserSoulEntity::getUpdaterName, operatorName)
                .set(UserSoulEntity::getVersion, nextVersion);
        if (existing.getId() != null) {
            wrapper.eq(UserSoulEntity::getId, existing.getId());
        } else {
            wrapper.eq(UserSoulEntity::getUserId, userId);
        }
        if (storedVersion == null) {
            wrapper.isNull(UserSoulEntity::getVersion);
        } else {
            wrapper.eq(UserSoulEntity::getVersion, storedVersion);
        }
        int updated = userSoulMapper.update(null, wrapper);
        if (updated != 1) {
            throw new IllegalArgumentException("SOUL_VERSION_CONFLICT: Soul 已被更新，请刷新后重试");
        }
        UserSoulEntity refreshed = findByUserId(userId);
        if (refreshed == null) {
            throw new IllegalStateException("更新后读取用户 Soul 失败: userId=" + userId);
        }
        return toResponse(refreshed);
    }

    private UserSoulEntity findByUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("用户未登录或登录态失效");
        }
        try {
            return userSoulMapper.selectOne(new LambdaQueryWrapper<UserSoulEntity>()
                    .eq(UserSoulEntity::getUserId, userId)
                    .orderByDesc(UserSoulEntity::getUpdatedAt)
                    .orderByDesc(UserSoulEntity::getId)
                    .last("limit 1"));
        } catch (BadSqlGrammarException ex) {
            if (isCompatColumnMismatch(ex)) {
                log.warn("UserSoul schema mismatch detected, fallback to compat query: userId={}, err={}",
                        userId, ex.getMessage());
                return userSoulMapper.selectLatestCompatByUserId(userId);
            }
            throw ex;
        }
    }

    private boolean isCompatColumnMismatch(BadSqlGrammarException ex) {
        String msg = ex.getMessage();
        return msg != null
                && (msg.contains("Unknown column 'content'")
                || msg.contains("Unknown column 'preset_id'")
                || msg.contains("Unknown column 'version'")
                || msg.contains("Unknown column 'updater_id'")
                || msg.contains("Unknown column 'updater_name'"));
    }

    private String normalizeContent(String content) {
        return content == null ? "" : content.trim();
    }

    private String normalizePresetId(String presetId) {
        if (!StringUtils.hasText(presetId)) {
            return null;
        }
        String normalized = presetId.trim();
        if (normalized.length() > MAX_PRESET_ID_LENGTH) {
            throw new IllegalArgumentException("SOUL_CONTENT_INVALID: presetId 长度不能超过 " + MAX_PRESET_ID_LENGTH);
        }
        return normalized;
    }

    private String normalizeOperatorName(String userName, String fallbackUserId) {
        String normalized = StringUtils.hasText(userName) ? userName.trim() : fallbackUserId;
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        return normalized.length() <= MAX_OPERATOR_NAME_LENGTH
                ? normalized
                : normalized.substring(0, MAX_OPERATOR_NAME_LENGTH);
    }

    private void validateContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("SOUL_CONTENT_INVALID: Soul 内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("SOUL_CONTENT_INVALID: Soul 内容长度不能超过 " + MAX_CONTENT_LENGTH);
        }
    }

    private UserSoulResponse toResponse(UserSoulEntity entity) {
        UserSoulResponse response = new UserSoulResponse();
        response.setContent(entity.getContent());
        response.setPresetId(entity.getPresetId());
        response.setVersion(normalizeVersion(entity.getVersion()));
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    private Long normalizeVersion(Long version) {
        if (version == null || version < 0) {
            return 0L;
        }
        return version;
    }
}
