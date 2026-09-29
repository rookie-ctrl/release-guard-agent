package com.interview.rag.agent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.time.Duration;
import java.util.List;

public final class ToolDefinitionFactory {

    private ToolDefinitionFactory() {
    }

    public static ToolDefinition stringArguments(String name, String description, ToolRiskLevel risk,
                                                  String... requiredProperties) {
        return stringArguments(name, description, risk, List.of(requiredProperties), List.of());
    }

    public static ToolDefinition stringArguments(String name, String description, ToolRiskLevel risk,
                                                  List<String> requiredProperties, List<String> optionalProperties) {
        JsonObjectSchema.Builder schema = JsonObjectSchema.builder().additionalProperties(false);
        for (String property : requiredProperties) {
            schema.addStringProperty(property);
        }
        for (String property : optionalProperties) {
            schema.addStringProperty(property);
        }
        schema.required(requiredProperties);
        ToolSpecification specification = ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(schema.build())
                .build();
        return new ToolDefinition(name, description, risk, Duration.ofSeconds(5), 1, specification);
    }
}
