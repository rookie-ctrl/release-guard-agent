package com.interview.rag.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.domain.ToolInvocation;
import com.interview.rag.agent.repository.ToolInvocationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolExecutionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolInvocationRepository invocationRepository = mock(ToolInvocationRepository.class);
    private final Map<String, ToolInvocation> invocations = new ConcurrentHashMap<>();

    @BeforeEach
    void setUpRepository() {
        when(invocationRepository.findByIdempotencyKey(anyString()))
                .thenAnswer(call -> Optional.ofNullable(invocations.get(call.getArgument(0))));
        when(invocationRepository.save(org.mockito.ArgumentMatchers.any(ToolInvocation.class)))
                .thenAnswer(call -> {
                    ToolInvocation invocation = call.getArgument(0);
                    invocations.put(invocation.getIdempotencyKey(), invocation);
                    return invocation;
                });
    }

    @Test
    void rejectsMissingRequiredArgumentBeforeCallingTool() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionService service = service(ToolRiskLevel.READ_ONLY, ignored -> calls.incrementAndGet());

        assertThatThrownBy(() -> service.execute("run-1", "inspect", "{}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("缺少必填工具参数");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsAdditionalArgumentBeforeCallingTool() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionService service = service(ToolRiskLevel.READ_ONLY, ignored -> calls.incrementAndGet());

        assertThatThrownBy(() -> service.execute("run-1", "inspect", "{\"service\":\"orders\",\"extra\":\"x\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("不允许的工具参数");
        assertThat(calls).hasValue(0);
    }

    @Test
    void requiresConfirmationBeforeCallingHighRiskTool() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionService service = service(ToolRiskLevel.HIGH_RISK, ignored -> calls.incrementAndGet());

        assertThatThrownBy(() -> service.execute("run-1", "inspect", "{\"service\":\"orders\"}"))
                .isInstanceOf(ConfirmationRequiredException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void rejectsForbiddenTool() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionService service = service(ToolRiskLevel.FORBIDDEN, ignored -> calls.incrementAndGet());

        assertThatThrownBy(() -> service.execute("run-1", "inspect", "{\"service\":\"orders\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessage("禁止调用该工具");
        assertThat(calls).hasValue(0);
    }

    @Test
    void replaysSuccessfulIdempotentReadWithoutCallingToolAgain() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionService service = service(ToolRiskLevel.READ_ONLY, ignored -> {
            calls.incrementAndGet();
            return Map.of("status", "passed");
        });

        ToolResult first = service.execute("run-1", "inspect", "{\"service\":\"orders\"}");
        ToolResult replay = service.execute("run-1", "inspect", "{\"service\":\"orders\"}");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.value()).isEqualTo(objectMapper.createObjectNode().put("status", "passed"));
        assertThat(calls).hasValue(1);
    }

    private ToolExecutionService service(ToolRiskLevel risk, java.util.function.Function<com.fasterxml.jackson.databind.JsonNode, Object> executor) {
        ToolDefinition definition = ToolDefinitionFactory.stringArguments("inspect", "Inspect a service", risk, "service");
        AgentTool tool = new GatewayAgentTool(definition, executor);
        return new ToolExecutionService(new ToolRegistry(List.of(tool)), invocationRepository, objectMapper,
                new SimpleMeterRegistry(), Runnable::run);
    }
}
