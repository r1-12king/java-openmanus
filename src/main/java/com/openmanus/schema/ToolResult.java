package com.openmanus.schema;

/**
 * 工具执行结果，对应 OpenManus 的 ToolResult
 */
public record ToolResult(
        /** 执行是否成功 */
        boolean success,

        /** 成功时的输出内容 */
        String output,

        /** 失败时的错误信息 */
        String error) {

    public static ToolResult success(String output) {
        return new ToolResult(true, output, null);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error);
    }

    /**
     * 返回适合写入 memory 的观察结果字符串
     */
    public String toObservation() {
        return success ? output : "Error: " + error;
    }
}
