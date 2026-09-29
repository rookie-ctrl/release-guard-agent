package com.interview.rag.agent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.time.Duration;

public record ToolDefinition(
        String name,
        String description,
        ToolRiskLevel riskLevel,
        Duration timeout,
        int maxRetries,
        ToolSpecification specification
) {
}
