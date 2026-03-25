package com.openmanus.controller;

import com.openmanus.entity.AgentRun;
import com.openmanus.schema.TaskStatusResponse;
import com.openmanus.service.AgentService;
import com.openmanus.service.SseEmitterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 任务状态查询 + SSE 实时事件流
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/task")
@RequiredArgsConstructor
public class TaskController {

    /** SSE 推送服务，管理连接生命周期和事件分发 */
    private final SseEmitterService sseEmitterService;

    /** Agent 服务，提供任务状态查询能力 */
    private final AgentService agentService;

    /**
     * 订阅任务事件流（SSE）
     * GET /api/agent/task/{taskId}/stream
     *
     * 事件格式：
     *   {"type":"step",     "step":1,  "content":"..."}   每步结果
     *   {"type":"complete",            "result":"..."}    任务完成
     *   {"type":"error",               "message":"..."}   任务失败
     */
    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String taskId) {
        log.info("SSE subscribe | taskId={}", taskId);
        return sseEmitterService.subscribe(taskId);
    }

    /**
     * 轮询任务状态
     * GET /api/agent/task/{taskId}/status
     */
    @GetMapping("/{taskId}/status")
    public ResponseEntity<TaskStatusResponse> status(@PathVariable String taskId) {
        AgentRun run = agentService.getTask(taskId);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new TaskStatusResponse(
                run.getTaskId(),
                run.getSessionId(),
                run.getStatus(),
                run.getStepsTaken(),
                run.getResult() != null ? run.getResult() : "",
                run.getCreatedAt().toString()
        ));
    }
}
