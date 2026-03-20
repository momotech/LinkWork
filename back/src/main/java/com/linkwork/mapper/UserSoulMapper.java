package com.linkwork.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkwork.model.entity.UserSoulEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserSoulMapper extends BaseMapper<UserSoulEntity> {

    @Select("""
            SELECT id,
                   user_id,
                   soul AS content,
                   template_id AS preset_id,
                   creator_id,
                   creator_name,
                   created_at,
                   updated_at,
                   is_deleted
            FROM linkwork_user_soul
            WHERE is_deleted = 0
              AND user_id = #{userId}
            ORDER BY updated_at DESC, id DESC
            LIMIT 1
            """)
    UserSoulEntity selectLatestCompatByUserId(@Param("userId") String userId);
}
