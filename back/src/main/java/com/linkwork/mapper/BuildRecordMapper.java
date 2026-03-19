package com.linkwork.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkwork.model.entity.LinkworkBuildRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BuildRecordMapper extends BaseMapper<LinkworkBuildRecord> {
}
