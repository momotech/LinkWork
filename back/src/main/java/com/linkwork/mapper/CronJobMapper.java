package com.linkwork.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkwork.model.entity.CronJob;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CronJobMapper extends BaseMapper<CronJob> {
}
