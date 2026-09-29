package com.interview.rag.agent.domain;

public enum AgentStepType {
    PLAN,
    TOOL_CALL,
    TOOL_RESULT,
    CONFIRMATION,
    FINAL_ANSWER,
    ERROR
}
