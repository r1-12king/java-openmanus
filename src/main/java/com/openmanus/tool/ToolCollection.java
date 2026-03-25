package com.openmanus.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmanus.schema.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 工具集合，负责工具注册和分发执行
 * 对应 OpenManus 的 ToolCollection
 */
@Slf4j
public class ToolCollection {

    /** 工具注册表，key=工具名，value=工具实例（保持插入顺序） */
    private final Map<String, BaseTool> tools = new LinkedHashMap<>();

    /** JSON 解析器，用于解析 LLM 返回的参数字符串 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolCollection(BaseTool... tools) {
        for (BaseTool tool : tools) {
            this.tools.put(tool.getName(), tool);
            log.info("Registered tool: {}", tool.getName());
        }
    }

    /**
     * 获取所有工具的 ToolSpecification 列表，传给 LangChain4j model.generate()
     */
    public List<ToolSpecification> getToolSpecifications() {
        return tools.values().stream()
                .map(BaseTool::getSpec)
                .toList();
    }

    /**
     * 根据工具名和 JSON 参数字符串执行工具
     * 由 ManusAgent 在 act() 阶段调用
     */
    public ToolResult execute(String toolName, String jsonArgs) {
        BaseTool tool = tools.get(toolName);
        if (tool == null) {
            return ToolResult.failure("Unknown tool: " + toolName);
        }
        try {
            Map<String, Object> args = objectMapper.readValue(
                    jsonArgs, new TypeReference<Map<String, Object>>() {});
            log.info("Executing tool '{}' args: {}", toolName, jsonArgs);
            ToolResult result = tool.execute(args);
            log.info("Tool '{}' -> {}", toolName,
                    result.success() ? "OK" : "FAIL: " + result.error());
            return result;
        } catch (Exception e) {
            log.error("Tool '{}' execution error: {}", toolName, e.getMessage());
            return ToolResult.failure(e.getMessage());
        }
    }

    public Collection<BaseTool> getTools() {
        return Collections.unmodifiableCollection(tools.values());
    }
}
