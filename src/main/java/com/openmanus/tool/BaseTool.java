package com.openmanus.tool;

import com.openmanus.schema.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.Map;

/**
 * 工具基础接口，对应 OpenManus 的 BaseTool
 * 每个工具实现此接口，提供 LangChain4j ToolSpecification 和执行逻辑
 */
public interface BaseTool {

    String getName();

    String getDescription();

    /**
     * 返回 LangChain4j 工具规格，用于传给 LLM 做 function calling
     */
    ToolSpecification getSpec();

    /**
     * 执行工具，args 为 LLM 传入的 JSON 参数解析后的 Map
     */
    ToolResult execute(Map<String, Object> args);
}
