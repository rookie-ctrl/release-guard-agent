package com.interview.rag.agent.tool;

public class ConfirmationRequiredException extends RuntimeException {

    private final String toolName;

    public ConfirmationRequiredException(String toolName) {
        super("此操作需要用户确认: " + toolName);
        this.toolName = toolName;
    }

    public String toolName() {
        return toolName;
    }
}
