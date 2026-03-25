package com.openmanus.tool;

import com.openmanus.schema.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文件操作工具，对应 OpenManus 的 FileOperators
 * 所有路径都限制在 workspaceDir 内，防止路径穿越
 */
@Slf4j
public class FileOperatorTool implements BaseTool {

    /** 工作目录，文件操作限制在此目录下（防路径穿越） */
    private final Path workspaceDir;

    public FileOperatorTool(String workspaceDir) {
        this.workspaceDir = Path.of(workspaceDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.workspaceDir);
        } catch (IOException e) {
            log.warn("Could not create workspace directory: {}", e.getMessage());
        }
    }

    @Override
    public String getName() {
        return "file_operator";
    }

    @Override
    public String getDescription() {
        return "Read, write, append, list, or delete files in the workspace. "
                + "Commands: read, write, append, list, delete.";
    }

    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
                .name(getName())
                .description(getDescription())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("command",
                                "Operation type: read | write | append | list | delete")
                        .addStringProperty("path",
                                "Relative file path within workspace (e.g. 'output.txt' or 'subdir/file.md')")
                        .addStringProperty("content",
                                "Content to write or append (required for write/append)")
                        .required("command", "path")
                        .build())
                .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String command = (String) args.get("command");
        String relativePath = (String) args.get("path");

        // 安全校验：防止路径穿越
        Path target = workspaceDir.resolve(relativePath).normalize();
        if (!target.startsWith(workspaceDir)) {
            return ToolResult.failure("Access denied: path is outside workspace");
        }

        try {
            return switch (command.toLowerCase()) {
                case "read" -> {
                    if (!Files.exists(target)) {
                        yield ToolResult.failure("File not found: " + relativePath);
                    }
                    yield ToolResult.success(Files.readString(target));
                }
                case "write" -> {
                    String content = (String) args.getOrDefault("content", "");
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, content,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    yield ToolResult.success("Written " + content.length()
                            + " chars to " + relativePath);
                }
                case "append" -> {
                    String content = (String) args.getOrDefault("content", "");
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, content,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    yield ToolResult.success("Appended " + content.length()
                            + " chars to " + relativePath);
                }
                case "list" -> {
                    Path dir = Files.isDirectory(target) ? target : target.getParent();
                    if (!Files.isDirectory(dir)) {
                        yield ToolResult.failure("Not a directory: " + relativePath);
                    }
                    String listing = Files.list(dir)
                            .map(p -> (Files.isDirectory(p) ? "[DIR] " : "[FILE] ")
                                    + p.getFileName())
                            .sorted()
                            .collect(Collectors.joining("\n"));
                    yield ToolResult.success(listing.isBlank() ? "(empty directory)" : listing);
                }
                case "delete" -> {
                    if (!Files.exists(target)) {
                        yield ToolResult.success("File already not found: " + relativePath);
                    }
                    Files.delete(target);
                    yield ToolResult.success("Deleted: " + relativePath);
                }
                default -> ToolResult.failure("Unknown command: " + command
                        + ". Use: read, write, append, list, delete");
            };
        } catch (IOException e) {
            return ToolResult.failure("File operation failed: " + e.getMessage());
        }
    }
}
