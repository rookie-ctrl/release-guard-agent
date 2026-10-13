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
            addProperty(schema, property);
        }
        for (String property : optionalProperties) {
            addProperty(schema, property);
        }
        schema.required(requiredProperties);
        ToolSpecification specification = ToolSpecification.builder()
                .name(name)
                .description(description)
                .parameters(schema.build())
                .build();
        return new ToolDefinition(name, description, risk, Duration.ofSeconds(5), 1, specification);
    }

    private static void addProperty(JsonObjectSchema.Builder schema, String property) {
        switch (property) {
            case "side" -> schema.addEnumProperty(property, List.of("HEAD", "BASE"));
            case "riskLevel" -> schema.addEnumProperty(property, List.of("LOW", "MEDIUM", "HIGH"));
            case "documentType" -> schema.addEnumProperty(property,
                    java.util.Arrays.stream(com.interview.rag.domain.DocumentType.values()).map(Enum::name).toList());
            default -> schema.addStringProperty(property);
        }
    }
}
