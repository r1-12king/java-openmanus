package com.openmanus.agent;

import com.openmanus.schema.AgentState;
import com.openmanus.schema.ToolResult;
import com.openmanus.tool.TerminateTool;
import com.openmanus.tool.ToolCollection;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 通用 Agent，整合 ReAct 架构（think + act 循环）
 * 对应 OpenManus 的 Manus + ToolCallAgent + ReActAgent
 *
 * think: 调用 LLM，获取下一步动作（工具调用 or 最终回答）
 * act:   执行工具，将结果写回 memory，进入下一轮 think
 */
@Slf4j
public class ManusAgent extends BaseAgent {

    /** LLM 实例，用于 think 阶段生成响应（OpenAI 兼容接口） */
    private final ChatModel model;

    /** 工具集合，包含所有可用工具，负责分发执行 */
    private final ToolCollection toolCollection;

    /** 工具输出最大字符数，超出截断，防止 token 爆炸 */
    private final int maxObserve;

    /** 最终结果，当 terminate 工具被调用时存储纯净的报告内容 */
    private String finalResult = null;

    public ManusAgent(ChatModel model,
                      ToolCollection toolCollection,
                      String systemPrompt,
                      int maxSteps,
                      int maxObserve) {
        super("Manus", "A general-purpose AI agent capable of using tools", systemPrompt, maxSteps);
        this.model = model;
        this.toolCollection = toolCollection;
        this.maxObserve = maxObserve;
    }

    /**
     * 获取最终结果（去掉 terminate 信号的纯净内容）
     */
    @Override
    protected String getFinalResult() {
        return finalResult;
    }

    /**
     * 单步执行 = think() + act()
     * 对应 OpenManus 的 ToolCallAgent.step()
     */
    @Override
    protected String step() {
        // ====== THINK ======
        List<ChatMessage> messages = buildMessages();

        // 如有 nextStepPrompt（卡死恢复），追加到最后一条用户消息后
        if (nextStepPrompt != null && !nextStepPrompt.isBlank()) {
            messages.add(dev.langchain4j.data.message.UserMessage.from(nextStepPrompt));
            // 用完即清
            nextStepPrompt = null;
        }

        log.info("[{}] Thinking... (memory size: {})", name, memory.size());

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolCollection.getToolSpecifications())
                .build());

        AiMessage aiMessage = response.aiMessage();
        memory.add(aiMessage);

        // LLM 直接回答，没有工具调用 → 任务完成
        if (!aiMessage.hasToolExecutionRequests()) {
            String text = aiMessage.text();
            log.info("[{}] No tool calls, finishing. Answer: {}",
                    name, truncate(text, 200));
            state = AgentState.FINISHED;
            // 存储最终结果
            finalResult = text != null ? text : "Task completed.";
            return finalResult;
        }

        // ====== ACT ======
        StringBuilder stepResult = new StringBuilder();
        List<dev.langchain4j.agent.tool.ToolExecutionRequest> requests =
                aiMessage.toolExecutionRequests();

        log.info("[{}] Acting: {} tool call(s)", name, requests.size());

        for (var toolRequest : requests) {
            String toolName = toolRequest.name();
            String toolArgs = toolRequest.arguments();

            log.info("[{}] Tool call: {} ({})", name, toolName, toolArgs);

            ToolResult result = toolCollection.execute(toolName, toolArgs);

            // 截断过长的工具输出，避免 token 爆炸
            String observation = truncate(result.toObservation(), maxObserve);

            // 将工具结果写回 memory，供下一轮 think 使用
            memory.add(ToolExecutionResultMessage.from(toolRequest, observation));

            stepResult.append("[").append(toolName).append("] ").append(observation).append("\n");

            // 检测 terminate 信号（在 observation 中查找）
            if (observation.contains(TerminateTool.TERMINATE_SIGNAL)) {
                state = AgentState.FINISHED;
                log.info("[{}] Terminate signal received, finishing", name);
                log.info("[{}] observation content (first 200 chars): {}", name,
                    observation.length() > 200 ? observation.substring(0, 200) + "..." : observation);
                // 提取纯净的报告内容（去掉 __TERMINATE__: 前缀）
                int idx = observation.indexOf(TerminateTool.TERMINATE_SIGNAL);
                log.info("[{}] TERMINATE_SIGNAL index: {}", name, idx);
                if (idx >= 0) {
                    finalResult = observation.substring(idx + TerminateTool.TERMINATE_SIGNAL.length()).trim();
                    log.info("[{}] finalResult after substring (first 100 chars): {}", name,
                        finalResult.length() > 100 ? finalResult.substring(0, 100) + "..." : finalResult);
                    // 去掉可能的前缀冒号和空格
                    if (finalResult.startsWith(":")) {
                        finalResult = finalResult.substring(1).trim();
                    }
                    log.info("[{}] finalResult extracted (first 100 chars): {}", name,
                        finalResult.length() > 100 ? finalResult.substring(0, 100) + "..." : finalResult);
                } else {
                    log.warn("[{}] Could not find TERMINATE_SIGNAL in observation", name);
                }
            }
        }

        return stepResult.toString().trim();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...[truncated, " + text.length() + " chars total]";
    }
}
