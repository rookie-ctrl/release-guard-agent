package com.interview.rag.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Function;

public class GatewayAgentTool implements AgentTool {

    private final ToolDefinition definition;
    private final Function<JsonNode, Object> executor;

    public GatewayAgentTool(ToolDefinition definition, Function<JsonNode, Object> executor) {
        this.definition = definition;
        this.executor = executor;
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public Object execute(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new ToolExecutionException("工具参数必须是 JSON 对象");
        }
        return executor.apply(arguments);
    }
}
