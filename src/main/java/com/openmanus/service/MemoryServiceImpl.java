package com.openmanus.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmanus.entity.AgentMemory;
import com.openmanus.mapper.AgentMemoryMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 对话记忆服务实现
 * 负责 ChatMessage ↔ AgentMemory（MySQL）的序列化/反序列化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryServiceImpl implements MemoryService {

    /** 记忆 Mapper，操作 agent_memory 表 */
    private final AgentMemoryMapper mapper;

    /** JSON 序列化器，用于解析 tool_calls 字段 */
    private final ObjectMapper objectMapper;

    @Override
    public List<ChatMessage> loadMemory(String sessionId) {
        return mapper.findBySessionIdOrderBySeqAsc(sessionId).stream()
                .map(this::toMessage)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    @Override
    public int countSavedMessages(String sessionId) {
        LambdaQueryWrapper<AgentMemory> qw = new LambdaQueryWrapper<>();
        qw.eq(AgentMemory::getSessionId, sessionId);
        return mapper.selectCount(qw).intValue();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveNewMessages(String sessionId, List<ChatMessage> allMessages, int alreadySavedCount) {
        for (int i = alreadySavedCount; i < allMessages.size(); i++) {
            AgentMemory record = toRecord(sessionId, i, allMessages.get(i));
            if (record != null) {
                mapper.insert(record);
            }
        }
    }

    // ==================== 反序列化 ====================

    private ChatMessage toMessage(AgentMemory r) {
        try {
            return switch (r.getRole()) {
                case "user" -> UserMessage.from(r.getContent());
                case "assistant" -> buildAiMessage(r);
                case "tool" -> ToolExecutionResultMessage.from(
                        ToolExecutionRequest.builder()
                                .id(r.getToolCallId())
                                .name(r.getToolName())
                                .arguments("{}")
                                .build(),
                        r.getContent()
                );
                default -> null;
            };
        } catch (Exception e) {
            log.warn("Failed to deserialize memory id={}: {}", r.getId(), e.getMessage());
            return null;
        }
    }

    private AiMessage buildAiMessage(AgentMemory r) throws Exception {
        if (r.getToolCalls() != null && !r.getToolCalls().isBlank()) {
            List<ToolCallJson> calls = objectMapper.readValue(
                    r.getToolCalls(), new TypeReference<>() {});
            List<ToolExecutionRequest> requests = calls.stream()
                    .map(c -> ToolExecutionRequest.builder()
                            .id(c.id()).name(c.name()).arguments(c.arguments())
                            .build())
                    .collect(Collectors.toList());
            String text = r.getContent();
            return (text != null && !text.isBlank())
                    ? new AiMessage(text, requests)
                    : AiMessage.from(requests);
        }
        return AiMessage.from(r.getContent() != null ? r.getContent() : "");
    }

    // ==================== 序列化 ====================

    private AgentMemory toRecord(String sessionId, int seq, ChatMessage message) {
        AgentMemory r = new AgentMemory();
        r.setSessionId(sessionId);
        r.setSeq(seq);
        try {
            if (message instanceof UserMessage um) {
                r.setRole("user");
                r.setContent(um.singleText());
            } else if (message instanceof AiMessage ai) {
                r.setRole("assistant");
                r.setContent(ai.text());
                if (ai.hasToolExecutionRequests()) {
                    List<ToolCallJson> calls = ai.toolExecutionRequests().stream()
                            .map(req -> new ToolCallJson(req.id(), req.name(), req.arguments()))
                            .collect(Collectors.toList());
                    r.setToolCalls(objectMapper.writeValueAsString(calls));
                }
            } else if (message instanceof ToolExecutionResultMessage tr) {
                r.setRole("tool");
                r.setContent(tr.text());
                r.setToolCallId(tr.id());
                r.setToolName(tr.toolName());
            } else {
                return null;
            }
        } catch (Exception e) {
            log.warn("Failed to serialize message type={}: {}", message.getClass().getSimpleName(), e.getMessage());
            return null;
        }
        return r;
    }

    record ToolCallJson(String id, String name, String arguments) {}
}
