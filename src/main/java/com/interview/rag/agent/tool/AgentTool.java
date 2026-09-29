package com.interview.rag.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface AgentTool {

    ToolDefinition definition();

    Object execute(JsonNode arguments);
}
