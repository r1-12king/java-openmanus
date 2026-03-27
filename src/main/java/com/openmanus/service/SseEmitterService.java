package com.openmanus.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 事件推送服务
 *
 * 设计：事件存储 + 实时推送
 * - 事件写入 eventStore（内存缓存），支持客户端晚连接时重放历史事件
 * - 同时推送给当前所有活跃的 SseEmitter
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmitterService {

    /** JSON 序列化器，将事件对象转为字符串 */
    private final ObjectMapper objectMapper;

    /** 每个 taskId 的历史事件列表（用于晚连接重放） */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> eventStore =
            new ConcurrentHashMap<>();

    /** 每个 taskId 的活跃 SSE 连接 */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters =
            new ConcurrentHashMap<>();

    /**
     * 客户端订阅任务事件流
     * 立即重放历史事件，然后注册为活跃连接
     */
    public SseEmitter subscribe(String taskId) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 分钟超时

        // 重放历史事件
        List<String> past = eventStore.getOrDefault(taskId, new CopyOnWriteArrayList<>());
        for (String json : past) {
            try {
                emitter.send(SseEmitter.event().data(json));
            } catch (IOException e) {
                log.warn("Failed to replay event for taskId={}: {}", taskId, e.getMessage());
                emitter.completeWithError(e);
                return emitter;
            }
        }

        // 注册活跃连接
        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(taskId, emitter));
        emitter.onTimeout(() -> removeEmitter(taskId, emitter));
        emitter.onError(e -> removeEmitter(taskId, emitter));

        log.debug("SSE subscribed | taskId={}", taskId);
        return emitter;
    }

    /** 推送步骤事件 */
    public void sendStep(String taskId, int step, String content) {
        publish(taskId, Map.of("type", "step", "step", step, "content", content));
    }

    /**
     * 直接推送自定义事件（供 PlanningFlow 使用）
     */
    public void publish(String taskId, Object event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            json = "{\"type\":\"error\",\"message\":\"serialization failed\"}";
        }

        eventStore.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(json);

        CopyOnWriteArrayList<SseEmitter> taskEmitters =
                emitters.getOrDefault(taskId, new CopyOnWriteArrayList<>());
        List<SseEmitter> dead = new java.util.ArrayList<>();
        for (SseEmitter emitter : taskEmitters) {
            try {
                emitter.send(SseEmitter.event().data(json));
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        taskEmitters.removeAll(dead);
    }

    /** 推送完成事件 */
    public void sendComplete(String taskId, String result) {
        publish(taskId, Map.of("type", "complete", "result", result));
        closeEmitters(taskId);
    }

    /** 推送错误事件 */
    public void sendError(String taskId, String message) {
        publish(taskId, Map.of("type", "error", "message", message));
        closeEmitters(taskId);
    }

    // ===== 内部方法 =====

    private void closeEmitters(String taskId) {
        CopyOnWriteArrayList<SseEmitter> taskEmitters = emitters.remove(taskId);
        if (taskEmitters != null) {
            taskEmitters.forEach(SseEmitter::complete);
        }
    }

    private void removeEmitter(String taskId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(taskId);
        if (list != null) list.remove(emitter);
    }
}
