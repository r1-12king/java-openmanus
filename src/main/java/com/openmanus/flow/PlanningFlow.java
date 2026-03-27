package com.openmanus.flow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openmanus.agent.ExecutorAgent;
import com.openmanus.agent.PlannerAgent;
import com.openmanus.entity.AgentRun;
import com.openmanus.mapper.AgentRunMapper;
import com.openmanus.schema.Plan;
import com.openmanus.service.SseEmitterService;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务规划执行流程，对应 OpenManus planning_flow.py
 *
 * 完整流程：
 * 1. PlannerAgent 生成结构化执行计划
 * 2. ExecutorAgent 按步骤逐一执行
 * 3. 每步通过 SSE 推送实时事件
 * 4. 最终汇总结果并持久化
 *
 * 使用方式：
 * <pre>
 * PlanningFlow flow = new PlanningFlow(plannerAgent, executorAgent, sseService, agentRunMapper);
 * flow.setTaskId(taskId);
 * flow.setRunId(runId);
 * flow.setSessionId(sessionId);
 * flow.execute(request);
 * </pre>
 */
@Slf4j
public class PlanningFlow {

    private final PlannerAgent plannerAgent;
    private final ExecutorAgent executorAgent;
    private final SseEmitterService sseEmitterService;
    private final AgentRunMapper agentRunMapper;
    private final ObjectMapper objectMapper;

    private String taskId;
    private Long runId;
    private String sessionId;

    public PlanningFlow(PlannerAgent plannerAgent,
                       ExecutorAgent executorAgent,
                       SseEmitterService sseEmitterService,
                       AgentRunMapper agentRunMapper) {
        this.plannerAgent = plannerAgent;
        this.executorAgent = executorAgent;
        this.sseEmitterService = sseEmitterService;
        this.agentRunMapper = agentRunMapper;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 执行完整的规划+执行流程
     *
     * @param request 用户请求
     * @return 最终执行结果
     */
    public String execute(String request) {
        log.info("[PlanningFlow] Starting | taskId={} | request={}", taskId, truncate(request, 80));

        try {
            // ========== 第一阶段：规划 ==========
            sendEvent("planning", "Generating execution plan...");

            Plan plan = plannerAgent.plan(request);

            // 发送计划生成完成事件
            sendPlanEvent(plan);
            sendEvent("planned", "Plan generated with " + plan.steps().size() + " steps");

            log.info("[PlanningFlow] Plan '{}' ready | {} steps", plan.title(), plan.steps().size());

            // ========== 第二阶段：执行计划 ==========
            AtomicReference<Plan> executingPlanRef = new AtomicReference<>(plan.withStatus(Plan.STATUS_EXECUTING));
            executorAgent.setPlan(executingPlanRef.get());

            // 监听每步执行结果，同步更新 Plan
            executorAgent.setExecutionListener((stepIndex, result) -> {
                Plan updated = executingPlanRef.get().withStepStatus(
                        stepIndex,
                        Plan.STEP_COMPLETED,
                        truncate(result, 500)
                );
                executingPlanRef.set(updated);
                sendStepEvent(stepIndex, result);
            });

            StringBuilder finalResults = new StringBuilder();
            List<Plan.PlanStep> steps = plan.steps();

            for (int i = 0; i < steps.size(); i++) {
                Plan.PlanStep step = steps.get(i);
                sendEvent("step_start", "Starting step " + i + ": " + step.description());

                String stepResult = executorAgent.executeStep(i);
                finalResults.append("Step ").append(i).append(": ").append(step.description()).append("\n")
                        .append("Result: ").append(stepResult).append("\n\n");

                log.info("[PlanningFlow] Step {} completed | {}", i, truncate(stepResult, 100));
            }

            // ========== 第三阶段：汇总结果 ==========
            Plan completedPlan = executingPlanRef.get().withStatus(Plan.STATUS_COMPLETED)
                    .withResult(finalResults.toString());

            // 更新数据库记录
            if (runId != null) {
                AgentRun run = agentRunMapper.selectById(runId);
                if (run != null) {
                    run.setResult(finalResults.toString());
                    run.setStepsTaken(steps.size());
                    run.setStatus("COMPLETED");
                    run.setCompletedAt(LocalDateTime.now());
                    agentRunMapper.updateById(run);
                }
            }

            // 发送完成事件
            sendCompleteEvent(finalResults.toString(), completedPlan);

            log.info("[PlanningFlow] Completed | taskId={} | steps={}", taskId, steps.size());
            return finalResults.toString();

        } catch (Exception e) {
            log.error("[PlanningFlow] Failed | taskId={} | error={}", taskId, e.getMessage(), e);

            // 更新数据库状态为失败
            if (runId != null) {
                AgentRun run = agentRunMapper.selectById(runId);
                if (run != null) {
                    run.setResult("PlanningFlow failed: " + e.getMessage());
                    run.setStatus("ERROR");
                    run.setCompletedAt(LocalDateTime.now());
                    agentRunMapper.updateById(run);
                }
            }

            sendErrorEvent(e.getMessage());
            return "Execution failed: " + e.getMessage();
        }
    }

    /**
     * 仅执行规划阶段，返回 Plan 对象
     */
    public Plan generatePlan(String request) {
        log.info("[PlanningFlow] Generating plan only | request={}", truncate(request, 80));
        return plannerAgent.plan(request);
    }

    /**
     * 仅执行计划步骤（需先通过 generatePlan 获取 Plan）
     */
    public String executePlan(Plan plan) {
        if (plan == null || plan.steps().isEmpty()) {
            return "No plan to execute";
        }

        executorAgent.setPlan(plan);
        StringBuilder results = new StringBuilder();

        for (int i = 0; i < plan.steps().size(); i++) {
            String result = executorAgent.executeStep(i);
            results.append("Step ").append(i).append(": ").append(result).append("\n");
            sendStepEvent(i, result);
        }

        return results.toString();
    }

    // ===== SSE 事件发送 =====

    private void sendEvent(String type, String message) {
        if (taskId != null) {
            sseEmitterService.sendStep(taskId, 0, "[" + type + "] " + message);
        }
    }

    private void sendPlanEvent(Plan plan) {
        if (taskId == null) return;
        try {
            PlanEvent event = new PlanEvent(
                    plan.planId(),
                    plan.title(),
                    plan.steps().size(),
                    plan.steps()
            );
            String json = objectMapper.writeValueAsString(event);
            sseEmitterService.publish(taskId, new PlanSseWrapper("plan", json));
        } catch (JsonProcessingException e) {
            log.warn("[PlanningFlow] Failed to serialize plan event", e);
        }
    }

    private void sendStepEvent(int stepIndex, String result) {
        if (taskId != null) {
            sseEmitterService.sendStep(taskId, stepIndex + 1, result);
        }
    }

    private void sendCompleteEvent(String result, Plan plan) {
        if (taskId != null) {
            try {
                CompletePayload payload = new CompletePayload(truncate(result, 2000), plan);
                sseEmitterService.publish(taskId, new PlanSseWrapper("plan_complete",
                        objectMapper.writeValueAsString(payload)));
            } catch (JsonProcessingException e) {
                log.warn("[PlanningFlow] Failed to serialize complete event", e);
                sseEmitterService.sendComplete(taskId, result);
            }
        }
    }

    private void sendErrorEvent(String message) {
        if (taskId != null) {
            sseEmitterService.sendError(taskId, message);
        }
    }

    // ===== 内部事件数据结构 =====

    /** SSE 事件包装器 */
    private record PlanSseWrapper(String type, String data) {}

    /** 计划生成事件 */
    private record PlanEvent(String planId, String title, int stepCount, List<Plan.PlanStep> steps) {}

    /** 计划完成事件负载 */
    private record CompletePayload(String result, Plan plan) {}

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text
                : text.substring(0, maxLength) + "...[truncated]";
    }

    // ===== Builder 风格静态工厂 =====

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private PlannerAgent plannerAgent;
        private ExecutorAgent executorAgent;
        private SseEmitterService sseEmitterService;
        private AgentRunMapper agentRunMapper;

        public Builder plannerAgent(PlannerAgent plannerAgent) {
            this.plannerAgent = plannerAgent;
            return this;
        }

        public Builder executorAgent(ExecutorAgent executorAgent) {
            this.executorAgent = executorAgent;
            return this;
        }

        public Builder sseEmitterService(SseEmitterService sseEmitterService) {
            this.sseEmitterService = sseEmitterService;
            return this;
        }

        public Builder agentRunMapper(AgentRunMapper agentRunMapper) {
            this.agentRunMapper = agentRunMapper;
            return this;
        }

        public PlanningFlow build() {
            return new PlanningFlow(plannerAgent, executorAgent, sseEmitterService, agentRunMapper);
        }
    }
}
