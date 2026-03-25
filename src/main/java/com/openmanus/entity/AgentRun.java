package com.openmanus.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 运行记录，持久化到 MySQL
 * 用于历史查询和审计
 */
@Data
@TableName("agent_runs")
public class AgentRun {

    /** 主键，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 单次异步任务 ID（UUID），用于 SSE 订阅和状态查询 */
    @TableField("task_id")
    private String taskId;

    /** 会话 ID，同一会话包含多次运行，支持多轮对话 */
    @TableField("session_id")
    private String sessionId;

    /** 用户请求原文 */
    @TableField("request")
    private String request;

    /** Agent 执行结果（最终回答或错误信息） */
    @TableField("result")
    private String result;

    /** 实际执行的步数 */
    @TableField("steps_taken")
    private int stepsTaken;

    /**
     * 状态：RUNNING / COMPLETED / ERROR
     */
    @TableField("status")
    private String status;

    /**
     * 创建时间，自动填充
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 完成时间
     */
    @TableField("completed_at")
    private LocalDateTime completedAt;
}
