package com.openmanus.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET /api/agent/task/{taskId}/status 响应体
 */
public record TaskStatusResponse(
        /** 任务 ID */
        @JsonProperty("task_id")
        String taskId,

        /** 会话 ID */
        @JsonProperty("session_id")
        String sessionId,

        /** 状态：RUNNING / COMPLETED / ERROR */
        String status,

        /** 已执行步数 */
        @JsonProperty("steps_taken")
        int stepsTaken,

        /** 执行结果（COMPLETED/ERROR 时有值） */
        String result,

        /** 任务创建时间 */
        @JsonProperty("created_at")
        String createdAt
) {}
