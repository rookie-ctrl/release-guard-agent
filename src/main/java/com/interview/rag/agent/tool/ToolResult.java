package com.interview.rag.agent.tool;

public record ToolResult(Object value, String invocationId, long durationMs, boolean replayed) {
}
