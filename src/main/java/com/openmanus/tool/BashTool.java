package com.openmanus.tool;

import com.openmanus.schema.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shell 命令执行工具，对应 OpenManus 的 Bash
 * 在 workspaceDir 下执行命令，支持超时控制
 */
@Slf4j
public class BashTool implements BaseTool {

    /** Bash 命令执行的工作目录，所有命令在此目录下运行 */
    private final File workspaceDir;

    /** 默认超时时间（秒），防止命令无限阻塞 */
    private final int defaultTimeoutSeconds;

    public BashTool(String workspaceDir, int defaultTimeoutSeconds) {
        this.workspaceDir = new File(workspaceDir);
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    @Override
    public String getName() {
        return "bash";
    }

    @Override
    public String getDescription() {
        return "Execute shell commands in the workspace directory. "
                + "Use for running scripts, checking files, installing packages, etc.";
    }

    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
                .name(getName())
                .description(getDescription())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("command", "The shell command to execute")
                        .addIntegerProperty("timeout",
                                "Timeout in seconds (default: " + defaultTimeoutSeconds + ")")
                        .required("command")
                        .build())
                .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String command = (String) args.get("command");
        int timeout = args.containsKey("timeout")
                ? ((Number) args.get("timeout")).intValue()
                : defaultTimeoutSeconds;

        log.info("Bash execute: {}", command);

        try {
            ProcessBuilder pb = new ProcessBuilder(List.of("/bin/bash", "-c", command));
            pb.directory(workspaceDir);
            pb.redirectErrorStream(true);  // stderr 合并到 stdout

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.failure(
                        "Command timed out after " + timeout + "s: " + command);
            }

            String result = output.toString().trim();
            int exitCode = process.exitValue();

            if (exitCode == 0) {
                return ToolResult.success(result.isEmpty() ? "(no output)" : result);
            } else {
                return ToolResult.failure("Exit code " + exitCode + ":\n" + result);
            }

        } catch (Exception e) {
            log.error("Bash execution failed: {}", e.getMessage());
            return ToolResult.failure("Execution error: " + e.getMessage());
        }
    }
}
