package com.openmanus.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /api/agent/run 请求体
 */
public record RunRequest(
        /** 用户请求内容 */
        String request,

        /** 会话 ID，不填则自动生成（用于多轮对话关联） */
        @JsonProperty("session_id")
        String sessionId
) {}
