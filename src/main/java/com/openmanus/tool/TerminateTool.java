package com.openmanus.tool;

import com.openmanus.schema.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.Map;

/**
 * 任务终止工具，对应 OpenManus 的 Terminate
 * LLM 调用此工具表示任务已完成，ManusAgent 检测 TERMINATE_SIGNAL 后结束主循环
 */
public class TerminateTool implements BaseTool {

    /** 任务终止信号，ManusAgent 检测到此字符串后结束主循环 */
    public static final String TERMINATE_SIGNAL = "__TERMINATE__";

    @Override
    public String getName() {
        return "terminate";
    }

    @Override
    public String getDescription() {
        return "Terminate the agent when the task is complete. Call this with a summary of what was accomplished.";
    }

    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
                .name(getName())
                .description(getDescription())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("message",
                                "Final summary of what was accomplished")
                        .required("message")
                        .build())
                .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String message = (String) args.getOrDefault("message", "Task completed.");
        return ToolResult.success(TERMINATE_SIGNAL + ": " + message);
    }
}
