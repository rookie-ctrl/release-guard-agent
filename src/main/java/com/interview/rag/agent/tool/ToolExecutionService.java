package com.interview.rag.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.domain.ExecutionStatus;
import com.interview.rag.agent.domain.ToolInvocation;
import com.interview.rag.agent.github.GitHubApiException;
import com.interview.rag.agent.repository.ToolInvocationRepository;
import com.interview.rag.agent.service.ExecutionLostException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
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
        return execute(runId, toolName, argumentsJson, false, () -> {});
    }

    public ToolResult execute(String runId, String toolName, String argumentsJson, Runnable checkExecution) {
        return execute(runId, toolName, argumentsJson, false, checkExecution);
    }

    public ToolResult executeConfirmed(String runId, String toolName, String argumentsJson) {
        throw new ToolExecutionException("写工具必须通过已持久化的审批操作执行");
    }

    private ToolResult execute(String runId, String toolName, String argumentsJson, boolean confirmed, Runnable checkExecution) {
        checkExecution.run();
        AgentTool tool = registry.get(toolName);
        ToolDefinition definition = tool.definition();
        if (definition.riskLevel() == ToolRiskLevel.FORBIDDEN) {
            throw new ToolExecutionException("禁止调用该工具");
        }
        if (definition.riskLevel() == ToolRiskLevel.HIGH_RISK && !confirmed) {
            throw new ConfirmationRequiredException(toolName);
        }

        JsonNode arguments = validateArguments(argumentsJson, definition);
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
        invocation = invocationRepository.save(invocation);

        long startedAt = System.nanoTime();
        Timer.Sample timer = Timer.start(meterRegistry);
        try {
            Object result = invokeWithRetries(runId, tool, arguments, definition, checkExecution);
            checkExecution.run();
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
            if (e instanceof ExecutionLostException lost) throw lost;
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

    public JsonNode validateArguments(String json, ToolDefinition definition) {
        try {
            if (json == null || json.length() > 16000) {
                throw new ToolExecutionException("工具参数为空或超过长度限制");
            }
            JsonNode arguments = objectMapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json);
            if (arguments == null || !arguments.isObject()) {
                throw new ToolExecutionException("工具参数必须是 JSON 对象");
            }
            for (String required : definition.specification().parameters().required()) {
                if (!arguments.hasNonNull(required) || arguments.path(required).asText().isBlank()) {
                    throw new ToolExecutionException("缺少必填工具参数: " + required);
                }
            }
            arguments.fields().forEachRemaining(entry -> {
                var schema = definition.specification().parameters().properties().get(entry.getKey());
                JsonNode value = entry.getValue();
                if (schema instanceof JsonStringSchema || schema instanceof JsonEnumSchema) {
                    if (!value.isTextual() || value.textValue().isBlank()
                            || value.textValue().length() > 4000 || value.textValue().indexOf('\0') >= 0) {
                        throw new ToolExecutionException("工具参数必须为非空且长度受限的字符串: " + entry.getKey());
                    }
                    if (schema instanceof JsonEnumSchema enumeration
                            && !enumeration.enumValues().contains(value.textValue())) {
                        throw new ToolExecutionException("工具参数枚举值无效: " + entry.getKey());
                    }
                } else if (schema != null) {
                    throw new ToolExecutionException("工具参数类型尚未提供后端校验: " + entry.getKey());
                }
            });
            if (Boolean.FALSE.equals(definition.specification().parameters().additionalProperties())) {
                arguments.fieldNames().forEachRemaining(name -> {
                    if (!definition.specification().parameters().properties().containsKey(name)) {
                        throw new ToolExecutionException("不允许的工具参数: " + name);
                    }
                });
            }
            validateBusinessArguments(definition.name(), arguments);
            return arguments;
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutionException("工具参数 JSON 无效", e);
        }
    }

    private void validateBusinessArguments(String toolName, JsonNode arguments) {
        if (java.util.Set.of("githubFiles", "githubDiff", "githubFileContext", "githubChecks").contains(toolName)) {
            for (String name : java.util.List.of("page", "startLine")) {
                if (!arguments.has(name)) continue;
                String value = arguments.path(name).asText();
                if (!value.matches("[1-9][0-9]{0,8}")
                        || ("page".equals(name) && Integer.parseInt(value) > 150)) {
                    throw new ToolExecutionException(name + " 必须为允许范围内的正整数");
                }
            }
        }
        if (arguments.has("filename") && toolName.startsWith("github")) {
            String filename = arguments.path("filename").asText();
            if (filename.length() > 1024 || filename.startsWith("/") || filename.contains("\\")
                    || java.util.Arrays.stream(filename.split("/", -1))
                    .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
                throw new ToolExecutionException("filename 必须为仓库内的相对路径");
            }
        }
        if ("githubFileContext".equals(toolName) && arguments.has("side")
                && !java.util.Set.of("HEAD", "BASE").contains(arguments.path("side").asText())) {
            throw new ToolExecutionException("side 必须为 HEAD 或 BASE");
        }
        if ("searchKnowledge".equals(toolName)) {
            try {
                com.interview.rag.domain.DocumentType.valueOf(arguments.path("documentType").asText());
            } catch (IllegalArgumentException e) {
                throw new ToolExecutionException("documentType 枚举值无效");
            }
        }
        if ("createReleaseApproval".equals(toolName)
                && !java.util.Set.of("LOW", "MEDIUM", "HIGH").contains(arguments.path("riskLevel").asText())) {
            throw new ToolExecutionException("riskLevel 枚举值无效");
        }
    }

    private Object invokeWithTimeout(String runId, AgentTool tool, JsonNode arguments, Duration timeout,
                                     Runnable checkExecution) throws Exception {
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
            checkExecution.run();
            return tool.execute(runId, arguments);
        }, toolExecutor);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } catch (Exception e) {
            future.cancel(true);
            throw e;
        }
    }

    private Object invokeWithRetries(String runId, AgentTool tool, JsonNode arguments, ToolDefinition definition,
                                     Runnable checkExecution) throws Exception {
        Exception lastError = null;
        int retries = definition.riskLevel() == ToolRiskLevel.READ_ONLY ? definition.maxRetries() : 0;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                checkExecution.run();
                return invokeWithTimeout(runId, tool, arguments, definition.timeout(), checkExecution);
            } catch (Exception e) {
                lastError = e;
                if (e instanceof ExecutionLostException) throw e;
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
