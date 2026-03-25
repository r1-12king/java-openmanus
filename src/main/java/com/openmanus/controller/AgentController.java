package com.openmanus.controller;

import com.openmanus.entity.AgentRun;
import com.openmanus.schema.RunRequest;
import com.openmanus.schema.RunResponse;
import com.openmanus.schema.SessionResultResponse;
import com.openmanus.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent REST API，对外暴露 Agent 能力
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    /** Agent 服务，负责任务提交、结果查询等业务逻辑 */
    private final AgentService agentService;

    /**
     * 异步提交任务，立即返回 taskId
     * POST /api/agent/run
     * Body: { "request": "帮我搜索今天的新闻", "session_id": "可选" }
     * Response: { "task_id": "...", "session_id": "...", "stream_url": "..." }
     */
    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody RunRequest body) {
        if (body.request() == null || body.request().isBlank()) {
            return ResponseEntity.badRequest().body("Field 'request' is required");
        }

        AgentService.TaskSubmitResponse resp = agentService.submitTask(body.request(), body.sessionId());

        return ResponseEntity.ok(new RunResponse(
                resp.taskId(),
                resp.sessionId(),
                "/api/agent/task/" + resp.taskId() + "/stream",
                "/api/agent/task/" + resp.taskId() + "/status"
        ));
    }

    /**
     * 获取会话最近结果
     * GET /api/agent/session/{sessionId}
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<SessionResultResponse> getResult(@PathVariable String sessionId) {
        return ResponseEntity.ok(new SessionResultResponse(sessionId, agentService.getResult(sessionId)));
    }

    /**
     * 获取会话运行历史
     * GET /api/agent/history/{sessionId}
     */
    @GetMapping("/history/{sessionId}")
    public ResponseEntity<List<AgentRun>> getHistory(@PathVariable String sessionId) {
        return ResponseEntity.ok(agentService.getHistory(sessionId));
    }

    /**
     * 健康检查
     * GET /api/agent/health
     */
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(new HealthResponse("ok", "Manus", "1.0.0"));
    }

    record HealthResponse(String status, String agent, String version) {}
}
