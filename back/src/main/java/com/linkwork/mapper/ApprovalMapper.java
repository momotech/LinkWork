package com.linkwork.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkwork.model.entity.Approval;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审批 Mapper
 */
@Mapper
public interface ApprovalMapper extends BaseMapper<Approval> {
}
