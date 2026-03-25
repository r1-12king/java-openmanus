package com.openmanus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openmanus.entity.AgentMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * AgentMemory Mapper，对应 agent_memory 表
 */
@Mapper
public interface AgentMemoryMapper extends BaseMapper<AgentMemory> {

    /**
     * 按 sessionId 升序查询所有消息
     */
    @Select("SELECT * FROM agent_memory WHERE session_id = #{sessionId} ORDER BY seq ASC")
    java.util.List<AgentMemory> findBySessionIdOrderBySeqAsc(@Param("sessionId") String sessionId);
}
