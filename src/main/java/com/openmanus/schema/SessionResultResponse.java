package com.openmanus.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET /api/agent/session/{sessionId} 响应体
 */
public record SessionResultResponse(
        /** 会话 ID */
        @JsonProperty("session_id")
        String sessionId,

        /** 该会话最近一次运行的执行结果 */
        String result
) {}
