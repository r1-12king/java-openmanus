package com.openmanus.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.openmanus.entity.AgentRun;
import com.openmanus.service.AgentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * PlanningFlow 专用 Controller
 *
 * 流程：
 * POST /api/agent/plan/run → 提交规划任务，立即返回 taskId
 * GET  /api/agent/task/{taskId}/stream → SSE 实时事件流（复用 TaskController）
 * GET  /api/agent/plan/{taskId} → 查询计划详情（包含各步骤状态）
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/plan")
@RequiredArgsConstructor
public class PlanningFlowController {

    private final AgentService agentService;

    /**
     * 提交规划模式任务
     *
     * POST /api/agent/plan/run
     * Body: { "request": "...", "session_id": "..." }
     *
     * 返回立即可用的 taskId，SSE 流在 /api/agent/task/{taskId}/stream
     */
    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody PlanRunRequest body) {
        if (body.request() == null || body.request().isBlank()) {
            return ResponseEntity.badRequest().body("Field 'request' is required");
        }
        AgentService.TaskSubmitResponse resp =
                agentService.submitPlanningTask(body.request(), body.sessionId());

        return ResponseEntity.ok(new PlanRunResponse(
                resp.taskId(),
                resp.sessionId(),
                "/api/agent/task/" + resp.taskId() + "/stream"
        ));
    }

    /**
     * 查询规划任务详情
     *
     * GET /api/agent/plan/{taskId}
     * 返回 AgentRun 记录（包含 status、steps_taken、result 等）
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<AgentRun> getPlan(@PathVariable String taskId) {
        AgentRun run = agentService.getTask(taskId);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(run);
    }

    // ===== 请求/响应 DTO =====

    public record PlanRunRequest(String request, String sessionId) {}

    public record PlanRunResponse(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("stream_url") String streamUrl) {}
}
