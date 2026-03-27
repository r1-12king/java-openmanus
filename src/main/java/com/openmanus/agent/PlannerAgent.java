package com.openmanus.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmanus.schema.Plan;
import com.openmanus.tool.ToolCollection;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 任务规划 Agent，对应 OpenManus planning_flow.py 的规划阶段
 *
 * 职责：
 * - 分析用户请求
 * - 生成结构化执行计划（JSON 格式）
 * - 输出 Plan 对象，包含步骤列表和工具分配
 */
@Slf4j
public class PlannerAgent {

    private static final String PLANNER_SYSTEM_PROMPT = """
            You are a task planning assistant. Your job is to analyze the user's request and create a clear, structured execution plan.

            For each step in the plan:
            - Provide a concise but specific description of what needs to be done
            - Assign an appropriate tool from: web_search, bash, str_replace_editor, file_operator, terminate
            - Steps should be ordered logically (prerequisites before dependents)
            - Keep each step focused on one concrete action

            Available tools:
            - web_search: Search the internet for information
            - bash: Run shell commands (analysis, scripts, system operations)
            - str_replace_editor: Edit text/code files (read, write, replace)
            - file_operator: Read/write/list files and directories
            - terminate: Mark task as complete (should be the last step)

            IMPORTANT: Respond ONLY with valid JSON in this exact format, no other text:
            {
              "title": "Brief plan title (max 50 chars)",
              "steps": [
                {"index": 0, "description": "Step description", "tool_name": "tool_name"},
                {"index": 1, "description": "Step description", "tool_name": "tool_name"}
              ]
            }

            Requirements:
            - Always include a terminate step as the last step
            - Maximum 8 steps
            - Each step description must be clear enough for an executor to understand without additional context
            - If the task is very simple (1-2 actions), just plan those steps
            """;

    /** LLM 模型 */
    private final ChatModel model;

    /** 工具集合，用于构建工具规格 */
    private final ToolCollection toolCollection;

    /** JSON 解析器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 对话记忆 */
    private final List<ChatMessage> memory = new ArrayList<>();

    public PlannerAgent(ChatModel model, ToolCollection toolCollection) {
        this.model = model;
        this.toolCollection = toolCollection;
    }

    /**
     * 根据用户请求生成执行计划
     *
     * @param request 用户请求
     * @return 生成的 Plan 对象
     */
    public Plan plan(String request) {
        String planId = UUID.randomUUID().toString();
        log.info("[Planner] Generating plan for: {}", truncate(request, 80));

        Plan initialPlan = Plan.create(planId, request);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(PLANNER_SYSTEM_PROMPT));
        messages.add(UserMessage.from("User request: " + request));

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolCollection.getToolSpecifications())
                .build());

        AiMessage aiMessage = response.aiMessage();
        String rawResponse = aiMessage.text() != null ? aiMessage.text() : "";

        log.info("[Planner] Raw response: {}", truncate(rawResponse, 300));
        memory.add(aiMessage);

        // 解析 JSON 计划
        Plan plan = parsePlan(planId, request, rawResponse);
        log.info("[Planner] Generated plan '{}' with {} steps", plan.title(), plan.steps().size());

        return plan;
    }

    /**
     * 从 LLM 响应中解析 JSON 计划
     */
    private Plan parsePlan(String planId, String request, String rawResponse) {
        String jsonStr = extractJson(rawResponse);

        try {
            JsonNode root = objectMapper.readTree(jsonStr);
            String title = root.has("title") ? root.get("title").asText() : "Untitled Plan";

            List<Plan.PlanStep> steps = new ArrayList<>();
            JsonNode stepsNode = root.get("steps");
            if (stepsNode != null && stepsNode.isArray()) {
                for (JsonNode stepNode : stepsNode) {
                    int index = stepNode.has("index")
                            ? stepNode.get("index").asInt() : steps.size();
                    String description = stepNode.has("description")
                            ? stepNode.get("description").asText() : "";
                    String toolName = stepNode.has("tool_name")
                            ? stepNode.get("tool_name").asText() : null;

                    steps.add(new Plan.PlanStep(
                            index,
                            description,
                            Plan.STEP_PENDING,
                            "",
                            toolName
                    ));
                }
            }

            return Plan.create(planId, request)
                    .withTitle(title)
                    .withStatus(Plan.STATUS_READY)
                    .withSteps(steps);

        } catch (JsonProcessingException e) {
            log.warn("[Planner] Failed to parse plan JSON, using fallback: {}", e.getMessage());
            // JSON 解析失败，生成默认计划
            return fallbackPlan(planId, request, rawResponse);
        }
    }

    /**
     * 从 LLM 原始响应中提取 JSON（处理 markdown 代码块包裹）
     */
    private String extractJson(String text) {
        String trimmed = text.trim();
        // 处理 ```json ... ``` 或 ``` ... ``` 包裹
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
        }
        int endBackticks = trimmed.lastIndexOf("```");
        if (endBackticks > 0) {
            trimmed = trimmed.substring(0, endBackticks);
        }
        return trimmed.trim();
    }

    /**
     * JSON 解析失败时的兜底方案：从原始文本中提取关键信息
     */
    private Plan fallbackPlan(String planId, String request, String rawResponse) {
        List<Plan.PlanStep> steps = new ArrayList<>();

        // 简单策略：将原始响应作为一个步骤执行
        if (!rawResponse.isBlank()) {
            steps.add(new Plan.PlanStep(0, truncate(rawResponse, 200), Plan.STEP_PENDING, "", null));
        } else {
            steps.add(new Plan.PlanStep(0, request, Plan.STEP_PENDING, "", "terminate"));
        }

        // 确保最后有 terminate 步骤
        boolean hasTerminate = steps.stream()
                .anyMatch(s -> "terminate".equals(s.toolName()));
        if (!hasTerminate) {
            steps.add(new Plan.PlanStep(1, "完成并返回结果", Plan.STEP_PENDING, "", "terminate"));
        }

        return Plan.create(planId, request)
                .withTitle(truncate(request, 40))
                .withStatus(Plan.STATUS_READY)
                .withSteps(steps);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    /**
     * 重置 PlannerAgent 状态
     */
    public void reset() {
        memory.clear();
    }
}
