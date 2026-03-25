package com.openmanus.tool;

import com.openmanus.schema.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 文件内字符串精确替换工具，对应 OpenManus 的 StrReplaceEditor
 * 读取文件 → 替换 old_str 为 new_str → 写回文件
 * 适合 LLM 对代码文件做精确修改，避免整体重写
 */
@Slf4j
public class StrReplaceEditorTool implements BaseTool {

    /** 工作目录，文件操作限制在此目录下（防路径穿越） */
    private final Path workspaceDir;

    public StrReplaceEditorTool(String workspaceDir) {
        this.workspaceDir = Path.of(workspaceDir).toAbsolutePath().normalize();
    }

    @Override
    public String getName() {
        return "str_replace_editor";
    }

    @Override
    public String getDescription() {
        return "Make precise edits to a file by replacing an exact string with new content. "
                + "The old_str must match exactly (including whitespace and indentation). "
                + "Use this for targeted code edits without rewriting the whole file.";
    }

    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
                .name(getName())
                .description(getDescription())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("path",
                                "Relative path to the file within workspace")
                        .addStringProperty("old_str",
                                "The exact string to find and replace (must match precisely)")
                        .addStringProperty("new_str",
                                "The string to replace it with")
                        .required("path", "old_str", "new_str")
                        .build())
                .build();
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String relativePath = (String) args.get("path");
        String oldStr = (String) args.get("old_str");
        String newStr = (String) args.get("new_str");

        // 安全校验
        Path target = workspaceDir.resolve(relativePath).normalize();
        if (!target.startsWith(workspaceDir)) {
            return ToolResult.failure("Access denied: path is outside workspace");
        }

        if (!Files.exists(target)) {
            return ToolResult.failure("File not found: " + relativePath);
        }

        try {
            String content = Files.readString(target);

            if (!content.contains(oldStr)) {
                return ToolResult.failure(
                        "old_str not found in file. Make sure it matches exactly (including spaces/newlines).\n"
                        + "Searched in: " + relativePath);
            }

            // 只替换第一次出现（与 OpenManus 行为一致）
            String newContent = content.replaceFirst(
                    java.util.regex.Pattern.quote(oldStr),
                    java.util.regex.Matcher.quoteReplacement(newStr));

            Files.writeString(target, newContent);

            return ToolResult.success(
                    "Successfully replaced in " + relativePath + ".\n"
                    + "Changed " + oldStr.length() + " chars -> " + newStr.length() + " chars.");

        } catch (IOException e) {
            return ToolResult.failure("File edit failed: " + e.getMessage());
        }
    }
}
