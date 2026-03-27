package com.openmanus.service;

import com.openmanus.entity.AgentRun;

import java.util.List;

/**
 * Agent 服务接口
 */
public interface AgentService {

    /**
     * 异步提交任务，立即返回 taskId
     */
    TaskSubmitResponse submitTask(String request, String sessionId);

    /**
     * 按 taskId 查询任务
     */
    AgentRun getTask(String taskId);

    /**
     * 获取会话最近结果（先查 Redis，再查 MySQL）
     */
    String getResult(String sessionId);

    /**
     * 获取会话的所有运行历史
     */
    List<AgentRun> getHistory(String sessionId);

    record TaskSubmitResponse(String taskId, String sessionId) {}

    /**
     * 提交规划模式任务（PlannerAgent + ExecutorAgent 两阶段执行）
     */
    TaskSubmitResponse submitPlanningTask(String request, String sessionId);
}
