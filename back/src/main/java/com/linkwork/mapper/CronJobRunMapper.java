package com.linkwork.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkwork.model.entity.CronJobRun;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CronJobRunMapper extends BaseMapper<CronJobRun> {
}
