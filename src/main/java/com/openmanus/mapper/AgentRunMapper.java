package com.openmanus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.openmanus.entity.AgentRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AgentRun Mapper，对应 agent_runs 表
 * 继承 BaseMapper 后自动获得 CRUD 能力
 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {

    /**
     * 按 sessionId 降序查询所有运行记录
     */
    @Select("SELECT * FROM agent_runs WHERE session_id = #{sessionId} ORDER BY created_at DESC")
    List<AgentRun> findBySessionIdOrderByCreatedAtDesc(@Param("sessionId") String sessionId);

    /**
     * 按 sessionId 查询最新一条记录
     */
    @Select("SELECT * FROM agent_runs WHERE session_id = #{sessionId} ORDER BY created_at DESC LIMIT 1")
    AgentRun findTopBySessionIdOrderByCreatedAtDesc(@Param("sessionId") String sessionId);

    /**
     * 按 taskId 查询
     */
    @Select("SELECT * FROM agent_runs WHERE task_id = #{taskId} LIMIT 1")
    AgentRun findByTaskId(@Param("taskId") String taskId);
}
