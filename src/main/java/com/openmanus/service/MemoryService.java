package com.openmanus.service;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 对话记忆服务接口
 */
public interface MemoryService {

    /**
     * 加载会话的历史消息（按 seq 升序）
     */
    List<ChatMessage> loadMemory(String sessionId);

    /**
     * 查询已持久化的消息数量
     */
    int countSavedMessages(String sessionId);

    /**
     * 将本次运行新增的消息持久化到 DB
     */
    void saveNewMessages(String sessionId, List<ChatMessage> allMessages, int alreadySavedCount);
}
