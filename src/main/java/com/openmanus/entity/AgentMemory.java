package com.openmanus.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话记忆持久化实体，对应 agent_memory 表
 * 支持跨重启的多轮对话
 */
@Data
@TableName("agent_memory")
public class AgentMemory {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID */
    @TableField("session_id")
    private String sessionId;

    /** 消息在会话中的顺序，从 0 开始 */
    @TableField("seq")
    private Integer seq;

    /** 消息角色：user | assistant | tool */
    @TableField("role")
    private String role;

    /** 消息文本内容 */
    @TableField("content")
    private String content;

    /** assistant 消息的工具调用列表（JSON：[{id, name, arguments}]） */
    @TableField("tool_calls")
    private String toolCalls;

    /** tool 结果消息对应的工具调用 id */
    @TableField("tool_call_id")
    private String toolCallId;

    /** tool 结果消息对应的工具名称 */
    @TableField("tool_name")
    private String toolName;

    /** 创建时间，自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
