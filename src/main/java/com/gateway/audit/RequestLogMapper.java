package com.gateway.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RequestLogMapper extends BaseMapper<RequestLog> {
}
