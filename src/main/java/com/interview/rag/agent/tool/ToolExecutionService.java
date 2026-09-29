package com.interview.rag.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.domain.ExecutionStatus;
import com.interview.rag.agent.domain.ToolInvocation;
import com.interview.rag.agent.github.GitHubApiException;
import com.interview.rag.agent.repository.ToolInvocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;

@Service
public class ToolExecutionService {

    private final ToolRegistry registry;
    private final ToolInvocationRepository invocationRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Executor toolExecutor;

    public ToolExecutionService(ToolRegistry registry, ToolInvocationRepository invocationRepository,
                                ObjectMapper objectMapper, MeterRegistry meterRegistry,
                                @Qualifier("agentToolExecutor") Executor toolExecutor) {
        this.registry = registry;
        this.invocationRepository = invocationRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.toolExecutor = toolExecutor;
    }

    public ToolResult execute(String runId, String toolName, String argumentsJson) {
        return execute(runId, toolName, argumentsJson, false);
    }

    public ToolResult executeConfirmed(String runId, String toolName, String argumentsJson) {
        return execute(runId, toolName, argumentsJson, true);
    }

    private ToolResult execute(String runId, String toolName, String argumentsJson, boolean confirmed) {
        AgentTool tool = registry.get(toolName);
        ToolDefinition definition = tool.definition();
        if (definition.riskLevel() == ToolRiskLevel.FORBIDDEN) {
            throw new ToolExecutionException("禁止调用该工具");
        }
        if (definition.riskLevel() == ToolRiskLevel.HIGH_RISK && !confirmed) {
            throw new ConfirmationRequiredException(toolName);
        }

        JsonNode arguments = parseAndValidate(argumentsJson, definition);
        String idempotencyKey = sha256(runId + "|" + toolName + "|" + arguments);
        var prior = invocationRepository.findByIdempotencyKey(idempotencyKey);
        if (prior.isPresent()) {
            ToolInvocation invocation = prior.get();
            if (invocation.getStatus() == ExecutionStatus.SUCCESS) {
                return new ToolResult(readResult(invocation.getResultJson()), invocation.getId(),
                        invocation.getDurationMs() == null ? 0 : invocation.getDurationMs(), true);
            }
            if (invocation.getStatus() == ExecutionStatus.RUNNING) {
                if (definition.riskLevel() != ToolRiskLevel.READ_ONLY) {
                    throw new ToolExecutionException("写工具调用状态不确定，需要人工核对后处理");
                }
                invocation.setStatus(ExecutionStatus.FAILED);
                invocation.setErrorMessage("上次进程中断，安全重试只读工具");
            }
        }

        ToolInvocation invocation = prior.orElseGet(ToolInvocation::new);
        if (invocation.getId() == null) {
            invocation.setId(UUID.randomUUID().toString());
            invocation.setRunId(runId);
            invocation.setToolName(toolName);
            invocation.setRiskLevel(definition.riskLevel());
            invocation.setIdempotencyKey(idempotencyKey);
        }
        invocation.setArgumentsJson(arguments.toString());
        invocation.setStatus(ExecutionStatus.RUNNING);
        invocation.setRetryCount(0);
        invocationRepository.save(invocation);

        long startedAt = System.nanoTime();
        Timer.Sample timer = Timer.start(meterRegistry);
        try {
            Object result = invokeWithRetries(runId, tool, arguments, definition);
            invocation.setResultJson(objectMapper.writeValueAsString(result));
            invocation.setStatus(ExecutionStatus.SUCCESS);
            invocation.setDurationMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            invocationRepository.save(invocation);
            timer.stop(Timer.builder("releaseguard.agent.tool.duration")
                    .tag("tool", toolName).register(meterRegistry));
            meterRegistry.counter("releaseguard.agent.tool.calls", "tool", toolName, "outcome", "success")
                    .increment();
            return new ToolResult(result, invocation.getId(), invocation.getDurationMs(), false);
        } catch (Exception e) {
            invocation.setStatus(ExecutionStatus.FAILED);
            invocation.setDurationMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            invocation.setErrorMessage(e.getMessage());
            invocationRepository.save(invocation);
            timer.stop(Timer.builder("releaseguard.agent.tool.duration")
                    .tag("tool", toolName).register(meterRegistry));
            meterRegistry.counter("releaseguard.agent.tool.calls", "tool", toolName, "outcome", "failure")
                    .increment();
            throw e instanceof ToolExecutionException toolException
                    ? toolException : new ToolExecutionException(e instanceof GitHubApiException
                    ? e.getMessage() : "工具执行失败: " + toolName, e);
        }
    }

    private JsonNode parseAndValidate(String json, ToolDefinition definition) {
        try {
            JsonNode arguments = objectMapper.readTree(json);
            if (arguments == null || !arguments.isObject()) {
                throw new ToolExecutionException("工具参数必须是 JSON 对象");
            }
            for (String required : definition.specification().parameters().required()) {
                if (!arguments.hasNonNull(required) || arguments.path(required).asText().isBlank()) {
                    throw new ToolExecutionException("缺少必填工具参数: " + required);
                }
            }
            if (Boolean.FALSE.equals(definition.specification().parameters().additionalProperties())) {
                arguments.fieldNames().forEachRemaining(name -> {
                    if (!definition.specification().parameters().properties().containsKey(name)) {
                        throw new ToolExecutionException("不允许的工具参数: " + name);
                    }
                });
            }
            return arguments;
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("工具参数 JSON 无效", e);
        }
    }

    private Object invokeWithTimeout(String runId, AgentTool tool, JsonNode arguments, Duration timeout) throws Exception {
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> tool.execute(runId, arguments), toolExecutor);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } catch (Exception e) {
            future.cancel(true);
            throw e;
        }
    }

    private Object invokeWithRetries(String runId, AgentTool tool, JsonNode arguments, ToolDefinition definition) throws Exception {
        Exception lastError = null;
        int retries = definition.riskLevel() == ToolRiskLevel.READ_ONLY ? definition.maxRetries() : 0;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return invokeWithTimeout(runId, tool, arguments, definition.timeout());
            } catch (Exception e) {
                lastError = e;
                if (e instanceof GitHubApiException githubError && githubError.statusCode() != 0) {
                    throw githubError;
                }
                if (attempt < retries) {
                    Thread.sleep(100L << attempt);
                }
            }
        }
        throw lastError;
    }

    private Object readResult(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ToolExecutionException("已保存的工具结果无法解析", e);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成工具幂等键", e);
        }
    }
}
