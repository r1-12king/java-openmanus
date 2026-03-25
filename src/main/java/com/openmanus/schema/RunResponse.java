package com.openmanus.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/agent/run 响应体
 */
public record RunResponse(
        /** 单次任务 ID，用于 SSE 订阅和状态查询 */
        @JsonProperty("task_id")
        String taskId,

        /** 会话 ID，同一会话多次请求共享 Memory */
        @JsonProperty("session_id")
        String sessionId,

        /** SSE 事件流地址，用于实时接收执行日志 */
        @JsonProperty("stream_url")
        String streamUrl,

        /** 任务状态查询地址 */
        @JsonProperty("status_url")
        String statusUrl
) {}
