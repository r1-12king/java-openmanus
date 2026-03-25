package com.openmanus.schema;

/**
 * Agent 生命周期状态，对应 OpenManus 的 AgentState
 */
public enum AgentState {
    IDLE,
    RUNNING,
    FINISHED,
    ERROR
}
