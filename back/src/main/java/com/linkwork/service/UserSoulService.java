package com.linkwork.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linkwork.mapper.UserSoulMapper;
import com.linkwork.model.dto.UserSoulResponse;
import com.linkwork.model.dto.UserSoulUpsertRequest;
import com.linkwork.model.entity.LinkworkUserSoul;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserSoulService {

    private static final int MAX_CONTENT_LENGTH = 8000;

    private final UserSoulMapper userSoulMapper;

    public UserSoulResponse getCurrentUserSoul(String userId) {
        LinkworkUserSoul entity = findByUserId(userId);
        if (entity == null) throw new IllegalArgumentException("Soul not configured for current user");
        return toResponse(entity);
    }

    public String getOptionalSoulContent(String userId) {
        LinkworkUserSoul entity = findByUserId(userId);
        if (entity == null) return "";
        return entity.getContent() == null ? "" : entity.getContent().trim();
    }

    @Transactional
    public UserSoulResponse upsertCurrentUserSoul(String userId, String userName, UserSoulUpsertRequest request) {
        String content = request.getContent() == null ? "" : request.getContent().trim();
        if (!StringUtils.hasText(content)) throw new IllegalArgumentException("Soul content cannot be empty");
        if (content.length() > MAX_CONTENT_LENGTH) throw new IllegalArgumentException("Soul content too long");
        String operatorName = StringUtils.hasText(userName) ? userName.trim() : userId;

        LinkworkUserSoul existing = findByUserId(userId);
        if (existing == null) {
            if (request.getVersion() != null && request.getVersion() != 0L) {
                throw new IllegalArgumentException("First save version must be 0");
            }
            LinkworkUserSoul entity = new LinkworkUserSoul();
            entity.setUserId(userId);
            entity.setContent(content);
            entity.setPresetId(request.getPresetId());
            entity.setVersion(1L);
            entity.setCreatorId(userId);
            entity.setCreatorName(operatorName);
            entity.setUpdaterId(userId);
            entity.setUpdaterName(operatorName);
            userSoulMapper.insert(entity);
            return toResponse(entity);
        }

        Long storedVersion = existing.getVersion() == null ? 0L : existing.getVersion();
        Long requestVersion = request.getVersion() == null ? 0L : request.getVersion();
        if (!storedVersion.equals(requestVersion)) {
            throw new IllegalArgumentException("Soul version conflict, please refresh and retry");
        }

        long nextVersion = storedVersion + 1;
        LambdaUpdateWrapper<LinkworkUserSoul> wrapper = new LambdaUpdateWrapper<LinkworkUserSoul>()
                .set(LinkworkUserSoul::getContent, content)
                .set(LinkworkUserSoul::getPresetId, request.getPresetId())
                .set(LinkworkUserSoul::getUpdaterId, userId)
                .set(LinkworkUserSoul::getUpdaterName, operatorName)
                .set(LinkworkUserSoul::getVersion, nextVersion)
                .eq(LinkworkUserSoul::getId, existing.getId())
                .eq(LinkworkUserSoul::getVersion, storedVersion);
        int updated = userSoulMapper.update(null, wrapper);
        if (updated != 1) throw new IllegalArgumentException("Soul version conflict, please refresh and retry");
        LinkworkUserSoul refreshed = findByUserId(userId);
        return toResponse(refreshed);
    }

    private LinkworkUserSoul findByUserId(String userId) {
        if (!StringUtils.hasText(userId)) throw new IllegalArgumentException("User not authenticated");
        return userSoulMapper.selectOne(new LambdaQueryWrapper<LinkworkUserSoul>()
                .eq(LinkworkUserSoul::getUserId, userId)
                .orderByDesc(LinkworkUserSoul::getUpdatedAt)
                .last("limit 1"));
    }

    private UserSoulResponse toResponse(LinkworkUserSoul entity) {
        UserSoulResponse resp = new UserSoulResponse();
        resp.setContent(entity.getContent());
        resp.setPresetId(entity.getPresetId());
        resp.setVersion(entity.getVersion() == null ? 0L : entity.getVersion());
        resp.setUpdatedAt(entity.getUpdatedAt());
        return resp;
    }
}
