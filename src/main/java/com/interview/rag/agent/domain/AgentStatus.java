package com.interview.rag.agent.domain;

public enum AgentStatus {
    CREATED,
    RUNNING,
    WAITING_USER_INPUT,
    WAITING_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELLED
}
