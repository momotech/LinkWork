package com.linkwork.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linkwork.model.entity.LinkworkApproval;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApprovalMapper extends BaseMapper<LinkworkApproval> {
}
