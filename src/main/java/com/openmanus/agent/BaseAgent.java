package com.openmanus.agent;

import com.openmanus.schema.AgentState;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Agent 基类，对应 OpenManus 的 BaseAgent
 *
 * 职责：
 * - 状态机管理 (IDLE → RUNNING → FINISHED/ERROR)
 * - 主循环控制 (max_steps)
 * - Memory 管理 (ChatMessage 列表)
 * - 卡死检测 (重复响应检测)
 */
@Slf4j
@Getter
public abstract class BaseAgent {

    /** Agent 名称，如 "Manus" */
    protected final String name;

    /** Agent 描述 */
    protected final String description;

    /** 系统提示词，描述 Agent 角色和行为规则 */
    @Setter
    protected String systemPrompt;

    /** 卡死恢复提示词，由 handleStuckState() 自动注入 */
    @Setter
    protected String nextStepPrompt;

    /** 每步完成后回调，参数：(stepNumber, stepResult)，用于 SSE 推送 */
    @Setter
    private BiConsumer<Integer, String> stepListener;

    /** 主循环最大步数，防止无限循环 */
    protected final int maxSteps;

    /** 当前已执行步数 */
    protected int currentStep = 0;

    /** Agent 生命周期状态：IDLE → RUNNING → FINISHED/ERROR */
    protected AgentState state = AgentState.IDLE;

    /** 对话记忆，存储完整历史消息（user / assistant / tool），传给 LLM */
    protected final List<ChatMessage> memory = new ArrayList<>();

    /** 卡死检测阈值：相同 AI 回复出现 >= 此次数判定为卡死 */
    private static final int DUPLICATE_THRESHOLD = 2;

    protected BaseAgent(String name, String description, String systemPrompt, int maxSteps) {
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;
        this.maxSteps = maxSteps;
    }

    /**
     * 主入口：执行任务，对应 OpenManus 的 BaseAgent.run()
     */
    public String run(String request) {
        if (state != AgentState.IDLE) {
            throw new IllegalStateException(
                    "Agent '" + name + "' is not idle, state: " + state);
        }

        if (request != null && !request.isBlank()) {
            memory.add(UserMessage.from(request));
        }

        state = AgentState.RUNNING;
        List<String> results = new ArrayList<>();

        try {
            while (currentStep < maxSteps && state != AgentState.FINISHED) {
                currentStep++;
                log.info("[{}] ===== Step {}/{} =====", name, currentStep, maxSteps);

                String stepResult = step();
                results.add("Step " + currentStep + ": " + stepResult);

                // 通知监听器（SSE 推送）
                if (stepListener != null) {
                    stepListener.accept(currentStep, stepResult);
                }

                // 卡死检测
                if (isStuck()) {
                    handleStuckState();
                }
            }

            if (currentStep >= maxSteps && state != AgentState.FINISHED) {
                results.add("Terminated: reached max steps (" + maxSteps + ")");
                log.warn("[{}] Reached max steps without finishing", name);
            }

        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("[{}] Error during run: {}", name, e.getMessage(), e);
            results.add("Error: " + e.getMessage());
        } finally {
            // state 复位（不重置 currentStep，由调用方读取后再 reset()）
            if (state != AgentState.ERROR) {
                state = AgentState.IDLE;
            }
        }

        return String.join("\n", results);
    }

    /**
     * 单步执行，由子类实现（think + act）
     */
    protected abstract String step();

    /**
     * 卡死检测：检查最近的 assistant 消息是否重复出现
     * 对应 OpenManus 的 is_stuck()
     */
    protected boolean isStuck() {
        if (memory.size() < 2) {
            return false;
        }

        // 找最后一条 AI 消息
        String lastContent = null;
        for (int i = memory.size() - 1; i >= 0; i--) {
            ChatMessage msg = memory.get(i);
            if (msg instanceof AiMessage ai && ai.text() != null && !ai.text().isBlank()) {
                lastContent = ai.text();
                break;
            }
        }
        if (lastContent == null) return false;

        // 统计相同内容出现次数
        final String content = lastContent;
        long duplicateCount = memory.stream()
                .filter(msg -> msg instanceof AiMessage ai
                        && content.equals(ai.text()))
                .count();

        return duplicateCount >= DUPLICATE_THRESHOLD;
    }

    /**
     * 处理卡死：注入恢复提示词，对应 OpenManus 的 handle_stuck_state()
     */
    protected void handleStuckState() {
        String stuckPrompt = "Observed duplicate responses. Consider new strategies "
                + "and avoid repeating ineffective paths already attempted.";
        nextStepPrompt = stuckPrompt
                + (nextStepPrompt != null ? "\n" + nextStepPrompt : "");
        log.warn("[{}] Stuck state detected, injecting recovery prompt", name);
    }

    /**
     * 构建完整消息列表（system + memory），传给 LLM
     */
    protected List<ChatMessage> buildMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.addAll(memory);
        return messages;
    }

    /**
     * 用历史消息初始化 Memory（跨重启多轮对话）
     */
    public void initMemory(List<ChatMessage> messages) {
        memory.clear();
        memory.addAll(messages);
        log.info("[{}] Memory initialized with {} messages", name, messages.size());
    }

    /**
     * 重置 Agent 状态，用于复用同一实例
     */
    public void reset() {
        memory.clear();
        currentStep = 0;
        state = AgentState.IDLE;
        nextStepPrompt = null;
        log.info("[{}] Agent reset", name);
    }
}
