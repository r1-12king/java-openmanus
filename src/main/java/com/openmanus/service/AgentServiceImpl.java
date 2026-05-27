package com.openmanus.service;

import com.openmanus.agent.ExecutorAgent;
import com.openmanus.agent.ManusAgent;
import com.openmanus.agent.PlannerAgent;
import com.openmanus.entity.AgentRun;
import com.openmanus.flow.PlanningFlow;
import com.openmanus.mapper.AgentRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Agent 服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    /** Spring BeanFactory，用于获取 prototype scope 的 ManusAgent 实例 */
    private final BeanFactory beanFactory;

    /** 运行记录 Mapper，操作 agent_runs 表 */
    private final AgentRunMapper agentRunMapper;

    /** Redis 模板，用于缓存最新结果 */
    private final StringRedisTemplate redisTemplate;

    /** 记忆服务，负责对话历史的序列化与反序列化 */
    private final MemoryService memoryService;

    /** SSE 推送服务，负责实时推送任务执行日志 */
    private final SseEmitterService sseEmitterService;

    /** Agent 任务线程池，执行异步任务 */
    private final ThreadPoolTaskExecutor agentTaskExecutor;

    /** Redis 缓存有效期：24 小时 */
    private static final Duration SESSION_TTL = Duration.ofHours(24);

    /** Redis 缓存 key 前缀，格式：session:result:{sessionId} */
    private static final String REDIS_KEY_PREFIX = "session:result:";

    @Override
    public TaskSubmitResponse submitTask(String request, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        String taskId = UUID.randomUUID().toString();
        String finalSessionId = sessionId;

        AgentRun run = new AgentRun();
        run.setTaskId(taskId);
        run.setSessionId(finalSessionId);
        run.setRequest(request);
        run.setStatus("RUNNING");
        run.setStepsTaken(0);
        agentRunMapper.insert(run);

        log.info("Task submitted | taskId={} | session={}", taskId, finalSessionId);

        agentTaskExecutor.submit(() ->
                executeTask(taskId, run.getId(), request, finalSessionId));

        return new TaskSubmitResponse(taskId, finalSessionId);
    }

    private void executeTask(String taskId, Long runId, String request, String sessionId) {
        log.info("Task started | taskId={} | session={}", taskId, sessionId);

        ManusAgent agent = beanFactory.getBean(ManusAgent.class);

        int previousMsgCount = memoryService.countSavedMessages(sessionId);
        if (previousMsgCount > 0) {
            agent.initMemory(memoryService.loadMemory(sessionId));
            log.info("Memory restored | session={} | messages={}", sessionId, previousMsgCount);
        }

        agent.setStepListener((step, content) ->
                sseEmitterService.sendStep(taskId, step, content));

        AgentRun run = agentRunMapper.selectById(runId);

        try {
            String result = agent.run(request);

            memoryService.saveNewMessages(sessionId, agent.getMemory(), previousMsgCount);

            log.info("Task result | taskId={} | result_len={}", taskId, result != null ? result.length() : 0);

            run.setResult(result);
            run.setStepsTaken(agent.getCurrentStep());
            run.setStatus("COMPLETED");
            run.setCompletedAt(LocalDateTime.now());
            agentRunMapper.updateById(run);

            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + sessionId, result, SESSION_TTL);

            sseEmitterService.sendComplete(taskId, result);

            log.info("Task completed | taskId={} | steps={}", taskId, agent.getCurrentStep());

        } catch (Exception e) {
            log.error("Task failed | taskId={} | error={}", taskId, e.getMessage(), e);
            run.setResult("Execution failed: " + e.getMessage());
            run.setStatus("ERROR");
            run.setCompletedAt(LocalDateTime.now());
            agentRunMapper.updateById(run);
            sseEmitterService.sendError(taskId, e.getMessage());
        }
    }

    @Override
    public TaskSubmitResponse submitPlanningTask(String request, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        String taskId = UUID.randomUUID().toString();
        String finalSessionId = sessionId;

        AgentRun run = new AgentRun();
        run.setTaskId(taskId);
        run.setSessionId(finalSessionId);
        run.setRequest(request);
        run.setStatus("RUNNING");
        run.setStepsTaken(0);
        agentRunMapper.insert(run);

        log.info("Planning task submitted | taskId={} | session={}", taskId, finalSessionId);

        agentTaskExecutor.submit(() ->
                executePlanningTask(taskId, run.getId(), request, finalSessionId));

        return new TaskSubmitResponse(taskId, finalSessionId);
    }

    private void executePlanningTask(String taskId, Long runId, String request, String sessionId) {
        log.info("Planning task started | taskId={} | session={}", taskId, sessionId);

        // 获取 prototype-scoped 的 Agent 实例
        PlannerAgent plannerAgent = beanFactory.getBean(PlannerAgent.class);
        ExecutorAgent executorAgent = beanFactory.getBean(ExecutorAgent.class);

        // 构建 PlanningFlow（每次执行创建新实例，保持状态隔离）
        PlanningFlow flow = PlanningFlow.builder()
                .plannerAgent(plannerAgent)
                .executorAgent(executorAgent)
                .sseEmitterService(sseEmitterService)
                .agentRunMapper(agentRunMapper)
                .build();
        flow.setTaskId(taskId);
        flow.setRunId(runId);
        flow.setSessionId(sessionId);

        try {
            String result = flow.execute(request);

            AgentRun run = agentRunMapper.selectById(runId);
            if (run != null) {
                run.setResult(result);
                run.setStatus("COMPLETED");
                run.setCompletedAt(LocalDateTime.now());
                agentRunMapper.updateById(run);
            }

            redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + sessionId, result, SESSION_TTL);

            log.info("Planning task completed | taskId={}", taskId);

        } catch (Exception e) {
            log.error("Planning task failed | taskId={} | error={}", taskId, e.getMessage(), e);
            AgentRun run = agentRunMapper.selectById(runId);
            if (run != null) {
                run.setResult("Planning failed: " + e.getMessage());
                run.setStatus("ERROR");
                run.setCompletedAt(LocalDateTime.now());
                agentRunMapper.updateById(run);
            }
            sseEmitterService.sendError(taskId, e.getMessage());
        }
    }

    @Override
    public String getResult(String sessionId) {
        String cached = redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + sessionId);
        if (cached != null) {
            return cached;
        }
        AgentRun run = agentRunMapper.findTopBySessionIdOrderByCreatedAtDesc(sessionId);
        return run != null ? run.getResult() : "No result found for session: " + sessionId;
    }

    @Override
    public List<AgentRun> getHistory(String sessionId) {
        return agentRunMapper.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    @Override
    public AgentRun getTask(String taskId) {
        return agentRunMapper.findByTaskId(taskId);
    }
}
