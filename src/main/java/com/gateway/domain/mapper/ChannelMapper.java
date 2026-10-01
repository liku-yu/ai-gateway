package com.gateway.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gateway.domain.Channel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ChannelMapper extends BaseMapper<Channel> {

    @Update("UPDATE gw_channel SET status = #{status} WHERE id = #{id}")
    int updateStatus(Long id, String status);
}
