package com.linkwork.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkwork.model.entity.LinkworkGitLabAuth;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GitLabAuthMapper extends BaseMapper<LinkworkGitLabAuth> {
}
