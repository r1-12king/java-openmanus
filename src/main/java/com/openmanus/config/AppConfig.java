package com.openmanus.config;

import com.openmanus.agent.ManusAgent;
import com.openmanus.tool.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;

/**
 * 核心 Bean 配置：LLM、ToolCollection、ManusAgent
 *
 * ManusAgent 使用 prototype scope，每次请求创建新实例
 * 避免多请求间共享 memory 导致的上下文污染
 */
@Configuration
public class AppConfig {

    // ==================== LLM 配置 ====================

    /** 第三方 LLM API 密钥 */
    @Value("${llm.api-key}")
    private String apiKey;

    /** LLM API 地址，支持 OpenAI 兼容接口，填写中转地址或 Ollama 地址 */
    @Value("${llm.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    /** 使用的模型名称，如 qwen3-max / gpt-4o */
    @Value("${llm.model:gpt-4o}")
    private String model;

    /** 生成温度，控制创造性：0=确定性输出，1=高随机性 */
    @Value("${llm.temperature:0.7}")
    private double temperature;

    /** 单次生成最大 token 数 */
    @Value("${llm.max-tokens:4096}")
    private int maxTokens;

    // ==================== Agent 配置 ====================

    /** 主循环最大步数，防止无限执行 */
    @Value("${agent.max-steps:20}")
    private int maxSteps;

    /** 工具输出最大字符数，超出截断 */
    @Value("${agent.max-observe:3000}")
    private int maxObserve;

    /** 工作目录，文件操作和 Bash 命令限制在此目录内 */
    @Value("${agent.workspace-dir:./workspace}")
    private String workspaceDir;

    /** Agent 系统提示词，定义角色和行为 */
    @Value("${agent.system-prompt}")
    private String systemPrompt;

    /**
     * LLM 模型 Bean（singleton）
     * 使用 OpenAI 兼容接口，修改 baseUrl 即可接入国内中转或本地 Ollama
     */
    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofMinutes(3))
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * 工具集合 Bean（singleton）
     * 所有工具在此注册，新增工具只需在此添加
     */
    @Bean
    public ToolCollection toolCollection() {
        return new ToolCollection(
                new WebSearchTool(),
                new FileOperatorTool(workspaceDir),
                new BashTool(workspaceDir, 30),
                new StrReplaceEditorTool(workspaceDir),
                new TerminateTool()
        );
    }

    /**
     * ManusAgent Bean（prototype scope）
     * 每次从 BeanFactory.getBean() 获取时创建新实例，保证 memory 隔离
     */
    @Bean
    @Scope("prototype")
    public ManusAgent manusAgent(ChatModel chatModel,
                                 ToolCollection toolCollection) {
        return new ManusAgent(chatModel, toolCollection,
                systemPrompt, maxSteps, maxObserve);
    }

    /**
     * Agent 任务线程池（singleton）
     * 用于异步执行 Agent 任务，避免阻塞 HTTP 请求线程
     */
    @Bean
    public ThreadPoolTaskExecutor agentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(25);
        executor.setThreadNamePrefix("agent-task-");
        executor.initialize();
        return executor;
    }
}
