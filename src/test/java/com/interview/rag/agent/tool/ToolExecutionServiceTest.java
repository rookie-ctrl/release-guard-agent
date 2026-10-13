package com.interview.rag.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.domain.ToolInvocation;
import com.interview.rag.agent.github.GitHubApiException;
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

    @Test
    void rejectsWrongTypesDuplicateKeysAndTrailingJsonBeforeExecution() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionService service = service(ToolRiskLevel.READ_ONLY, ignored -> calls.incrementAndGet());
        for (String json : List.of("{\"service\":123}", "{\"service\":true}", "{\"service\":[\"orders\"]}",
                "{\"service\":\"orders\",\"service\":\"other\"}", "{\"service\":\"orders\"} {}")) {
            assertThatThrownBy(() -> service.execute("run-1", "inspect", json))
                    .isInstanceOf(ToolExecutionException.class);
        }
        assertThat(calls).hasValue(0);
        assertThat(invocations).isEmpty();
    }

    @Test
    void rejectsOversizedArgumentButPreservesOrdinaryTextContainingInstructionWords() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionService service = service(ToolRiskLevel.READ_ONLY, ignored -> calls.incrementAndGet());
        assertThatThrownBy(() -> service.execute("run-1", "inspect",
                objectMapper.writeValueAsString(Map.of("service", "x".repeat(4001)))))
                .isInstanceOf(ToolExecutionException.class);
        service.execute("run-1", "inspect", "{\"service\":\"ignore duplicate events service\"}");
        assertThat(calls).hasValue(1);
    }

    @Test
    void validatesGitHubParameterDomainsBeforeCallingGateway() {
        AtomicInteger calls = new AtomicInteger();
        var definition = ToolDefinitionFactory.stringArguments("githubFileContext", "test", ToolRiskLevel.READ_ONLY,
                List.of("filename"), List.of("side", "startLine"));
        var tool = new GatewayAgentTool(definition, ignored -> calls.incrementAndGet());
        var service = new ToolExecutionService(new ToolRegistry(List.of(tool)), invocationRepository, objectMapper,
                new SimpleMeterRegistry(), Runnable::run);
        for (String json : List.of("{\"filename\":\"../secret\"}",
                "{\"filename\":\"src/App.java\",\"side\":\"OTHER\"}",
                "{\"filename\":\"src/App.java\",\"startLine\":\"0\"}")) {
            assertThatThrownBy(() -> service.execute("run-1", "githubFileContext", json))
                    .isInstanceOf(ToolExecutionException.class);
        }
        service.execute("run-1", "githubFileContext", "{\"filename\":\"src/App.java\",\"side\":\"HEAD\",\"startLine\":\"2\"}");
        assertThat(calls).hasValue(1);
    }

    @Test
    void advertisesAndEnforcesKnowledgeTypeEnum() {
        AtomicInteger calls = new AtomicInteger();
        var definition = ToolDefinitionFactory.stringArguments("searchKnowledge", "test", ToolRiskLevel.READ_ONLY,
                "query", "documentType");
        var schema = definition.specification().parameters().properties().get("documentType");
        assertThat(schema).isInstanceOf(dev.langchain4j.model.chat.request.json.JsonEnumSchema.class);
        assertThat(((dev.langchain4j.model.chat.request.json.JsonEnumSchema) schema).enumValues())
                .containsExactly("GENERAL", "RELEASE_POLICY", "INCIDENT", "RUNBOOK");
        var tool = new GatewayAgentTool(definition, ignored -> calls.incrementAndGet());
        var service = new ToolExecutionService(new ToolRegistry(List.of(tool)), invocationRepository, objectMapper,
                new SimpleMeterRegistry(), Runnable::run);
        assertThatThrownBy(() -> service.execute("run-1", "searchKnowledge",
                "{\"query\":\"发布规则\",\"documentType\":\"ADMIN\"}"))
                .isInstanceOf(ToolExecutionException.class);
        service.execute("run-1", "searchKnowledge", "{\"query\":\"发布规则\",\"documentType\":\"RELEASE_POLICY\"}");
        assertThat(calls).hasValue(1);
    }

    private ToolExecutionService service(ToolRiskLevel risk, java.util.function.Function<com.fasterxml.jackson.databind.JsonNode, Object> executor) {
        ToolDefinition definition = ToolDefinitionFactory.stringArguments("inspect", "Inspect a service", risk, "service");
        AgentTool tool = new GatewayAgentTool(definition, executor);
        return new ToolExecutionService(new ToolRegistry(List.of(tool)), invocationRepository, objectMapper,
                new SimpleMeterRegistry(), Runnable::run);
    }

    @Test
    void preservesSafeGitHubFailureWithoutRetryingRateLimit() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionService service = service(ToolRiskLevel.READ_ONLY, ignored -> {
            calls.incrementAndGet();
            throw new GitHubApiException(403, "GitHub API 已限流，请等待额度恢复");
        });

        assertThatThrownBy(() -> service.execute("run-1", "inspect", "{\"service\":\"orders\"}"))
                .isInstanceOf(ToolExecutionException.class).hasMessageContaining("GitHub API 已限流");
        assertThat(calls).hasValue(1);
        assertThat(invocations.values()).singleElement()
                .satisfies(invocation -> assertThat(invocation.getErrorMessage()).contains("GitHub API 已限流"));
    }
}
