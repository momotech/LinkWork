package com.linkwork.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkwork.model.entity.GitLabAuthEntity;
import org.apache.ibatis.annotations.*;

@Mapper
public interface GitLabAuthMapper extends BaseMapper<GitLabAuthEntity> {

    @Select("SELECT * FROM linkwork_user_auth_gitlab WHERE user_id = #{userId} AND gitlab_id = #{gitlabId} AND scope = #{scope} LIMIT 1")
    GitLabAuthEntity selectIncludingDeleted(@Param("userId") String userId, @Param("gitlabId") Long gitlabId, @Param("scope") String scope);

    @Update("UPDATE linkwork_user_auth_gitlab SET user_id=#{userId}, gitlab_id=#{gitlabId}, username=#{username}, " +
            "name=#{name}, avatar_url=#{avatarUrl}, access_token=#{accessToken}, refresh_token=#{refreshToken}, " +
            "token_alias=#{tokenAlias}, expires_at=#{expiresAt}, scope=#{scope}, " +
            "created_at=#{createdAt}, updated_at=#{updatedAt}, is_deleted=#{isDeleted} WHERE id=#{id}")
    int updateIncludingDeleted(GitLabAuthEntity entity);
}
