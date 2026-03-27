package com.openmanus.agent;

import com.openmanus.schema.AgentState;
import com.openmanus.schema.Plan;
import com.openmanus.schema.ToolResult;
import com.openmanus.tool.TerminateTool;
import com.openmanus.tool.ToolCollection;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 执行 Agent，对应 OpenManus planning_flow.py 的执行阶段
 *
 * 职责：
 * - 执行计划中的单个步骤（基于 PlanStep）
 * - 将步骤执行结果写回 Plan
 * - 支持 SSE 事件推送
 */
@Slf4j
public class ExecutorAgent extends BaseAgent {

    /** LLM 模型 */
    private final ChatModel model;

    /** 工具集合 */
    private final ToolCollection toolCollection;

    /** 工具输出最大字符数 */
    private final int maxObserve;

    /** 当前执行的计划 */
    private Plan currentPlan;

    /** 当前正在执行的步骤索引 */
    private int currentStepIndex = -1;

    /** 步骤执行结果回调，(stepIndex, result) */
    private BiConsumer<Integer, String> executionListener;

    public ExecutorAgent(ChatModel model,
                         ToolCollection toolCollection,
                         String systemPrompt,
                         int maxSteps,
                         int maxObserve) {
        super("Executor", "Executes structured plan steps", systemPrompt, maxSteps);
        this.model = model;
        this.toolCollection = toolCollection;
        this.maxObserve = maxObserve;
    }

    /**
     * 设置执行结果监听器
     */
    public void setExecutionListener(BiConsumer<Integer, String> listener) {
        this.executionListener = listener;
    }

    /**
     * 设置当前计划
     */
    public void setPlan(Plan plan) {
        this.currentPlan = plan;
        this.currentStepIndex = -1;
        reset();
    }

    /**
     * 执行单个计划步骤
     *
     * @param stepIndex 步骤索引
     * @return 步骤执行结果
     */
    public String executeStep(int stepIndex) {
        if (currentPlan == null || stepIndex < 0 || stepIndex >= currentPlan.steps().size()) {
            throw new IllegalArgumentException("Invalid step index or no plan set");
        }

        Plan.PlanStep step = currentPlan.steps().get(stepIndex);
        this.currentStepIndex = stepIndex;
        this.state = AgentState.RUNNING;

        log.info("[Executor] Executing step {}: {}", stepIndex, step.description());

        // 将步骤描述作为用户请求
        String stepRequest = buildStepRequest(step);
        memory.add(UserMessage.from(stepRequest));

        try {
            String result = step();
            notifyExecution(stepIndex, result);
            return result;
        } catch (Exception e) {
            log.error("[Executor] Step {} failed: {}", stepIndex, e.getMessage());
            String errorResult = "Step failed: " + e.getMessage();
            notifyExecution(stepIndex, errorResult);
            return errorResult;
        }
    }

    /**
     * 执行到指定步骤或终止信号
     *
     * @param maxStepIndex 最大执行到的步骤索引（不含）
     * @return 执行结果
     */
    public String executeUpTo(int maxStepIndex) {
        if (currentPlan == null) return "No plan set";

        StringBuilder results = new StringBuilder();
        for (int i = 0; i < maxStepIndex && i < currentPlan.steps().size(); i++) {
            if (state == AgentState.FINISHED) break;
            String r = executeStep(i);
            results.append("Step ").append(i).append(": ").append(r).append("\n");
        }
        return results.toString();
    }

    /**
     * 单步执行 = think() + act()
     */
    @Override
    protected String step() {
        List<ChatMessage> messages = buildMessages();

        if (nextStepPrompt != null && !nextStepPrompt.isBlank()) {
            messages.add(UserMessage.from(nextStepPrompt));
            nextStepPrompt = null;
        }

        log.info("[Executor] Thinking... (memory size: {})", memory.size());

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolCollection.getToolSpecifications())
                .build());

        AiMessage aiMessage = response.aiMessage();
        memory.add(aiMessage);

        // 无工具调用 → 步骤完成
        if (!aiMessage.hasToolExecutionRequests()) {
            String text = aiMessage.text();
            log.info("[Executor] No tool calls, step done: {}", truncate(text, 100));
            return text != null ? text : "Done.";
        }

        // 执行工具调用
        StringBuilder stepResult = new StringBuilder();
        List<dev.langchain4j.agent.tool.ToolExecutionRequest> requests =
                aiMessage.toolExecutionRequests();

        log.info("[Executor] Acting: {} tool call(s)", requests.size());

        for (var toolRequest : requests) {
            String toolName = toolRequest.name();
            String toolArgs = toolRequest.arguments();

            log.info("[Executor] Tool call: {} ({})", toolName, toolArgs);

            ToolResult result = toolCollection.execute(toolName, toolArgs);
            String observation = truncate(result.toObservation(), maxObserve);

            memory.add(ToolExecutionResultMessage.from(toolRequest, observation));
            stepResult.append("[").append(toolName).append("] ").append(observation).append("\n");

            // 检测 terminate 信号 → 结束执行
            if (observation.contains(TerminateTool.TERMINATE_SIGNAL)) {
                state = AgentState.FINISHED;
                log.info("[Executor] Terminate signal received");
            }
        }

        return stepResult.toString().trim();
    }

    /**
     * 构建步骤请求上下文
     */
    private String buildStepRequest(Plan.PlanStep step) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please execute the following step:\n\n");
        sb.append("Step ").append(step.index()).append(": ").append(step.description()).append("\n\n");

        if (step.toolName() != null && !step.toolName().isBlank()) {
            sb.append("Recommended tool: ").append(step.toolName()).append("\n\n");
        }

        sb.append("Execute this step and report the result.");
        return sb.toString();
    }

    /**
     * 通知执行结果
     */
    private void notifyExecution(int stepIndex, String result) {
        if (executionListener != null) {
            executionListener.accept(stepIndex, result);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text
                : text.substring(0, maxLength) + "...[truncated]";
    }
}
