package com.interview.rag.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.config.AgentProperties;
import com.interview.rag.agent.domain.*;
import com.interview.rag.agent.repository.*;
import com.interview.rag.agent.tool.*;
import com.interview.rag.config.ChatModelManager;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AgentRunner {

    private static final int MAX_TOOL_RESULT_CHARS = 12000;

    private final AgentProperties properties;
    private final ChatModelManager chatModelManager;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionService toolExecutionService;
    private final ConversationMemoryService memoryService;
    private final AgentSessionRepository sessionRepository;
    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final AgentMessageRepository messageRepository;
    private final ConfirmationRequestRepository confirmationRepository;
    private final AgentEventService eventService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public AgentRunner(AgentProperties properties, ChatModelManager chatModelManager, ToolRegistry toolRegistry,
                       ToolExecutionService toolExecutionService, ConversationMemoryService memoryService,
                       AgentSessionRepository sessionRepository, AgentRunRepository runRepository,
                       AgentStepRepository stepRepository, AgentMessageRepository messageRepository,
                       ConfirmationRequestRepository confirmationRepository, AgentEventService eventService,
                       ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.chatModelManager = chatModelManager;
        this.toolRegistry = toolRegistry;
        this.toolExecutionService = toolExecutionService;
        this.memoryService = memoryService;
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.messageRepository = messageRepository;
        this.confirmationRepository = confirmationRepository;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public void process(String runId) {
        execute(runId);
    }

    private void execute(String runId) {
        long startedAt = System.currentTimeMillis();
        AgentRun run = requireRun(runId);
        if (run.getStatus() == AgentStatus.COMPLETED || run.getStatus() == AgentStatus.FAILED
                || run.getStatus() == AgentStatus.CANCELLED
                || run.getStatus() == AgentStatus.WAITING_CONFIRMATION) {
            return;
        }
        meterRegistry.counter("releaseguard.agent.runs", "outcome", "started").increment();
        eventService.publish(runId, "runStarted", Map.of("runId", runId));
        try {
            run.setStatus(AgentStatus.RUNNING);
            runRepository.save(run);
            List<ChatMessage> messages = memoryService.buildMessages(run.getSessionId());
            boolean pullRequestMode = sessionRepository.findById(run.getSessionId()).orElseThrow()
                    .getPullRequestContextJson() != null;
            int toolCalls = run.getToolCallCount() == null ? 0 : run.getToolCallCount();
            eventService.publish(runId, "plan", Map.of("message", pullRequestMode
                    ? "正在读取 GitHub PR、代码变更、CI 和知识库规范" : "正在检查演示发布信息、测试、数据库变更和服务依赖"));

            for (int step = 0; step < properties.maxSteps(); step++) {
                if (System.currentTimeMillis() - startedAt > properties.runTimeoutSeconds() * 1000L) {
                    throw new IllegalStateException("Agent 执行超过总时限");
                }
                ChatResponse response = chatModelManager.nonStreamingChatModel().chat(ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(toolRegistry.specifications(pullRequestMode))
                        .build());
                AiMessage aiMessage = response.aiMessage();
                messages.add(aiMessage);
                if (!aiMessage.hasToolExecutionRequests()) {
                    completeRun(run, aiMessage.text(), startedAt, response);
                    return;
                }

                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    if (!toolRegistry.allowedInMode(request.name(), pullRequestMode)) {
                        throw new ToolExecutionException("当前评审模式不允许调用工具：" + request.name());
                    }
                    if (++toolCalls > properties.maxToolCalls()) {
                        throw new IllegalStateException("Agent 工具调用次数超过限制");
                    }
                    run.setToolCallCount(toolCalls);
                    runRepository.save(run);
                    AgentTool tool = toolRegistry.get(request.name());
                    eventService.publish(runId, "toolCall", Map.of("tool", request.name(),
                            "riskLevel", tool.definition().riskLevel(), "arguments", parseJson(request.arguments())));

                    if (tool.definition().riskLevel() == ToolRiskLevel.HIGH_RISK) {
                        requestConfirmation(run, request);
                        return;
                    }

                    ToolResult result = toolExecutionService.execute(runId, request.name(), request.arguments());
                    String resultJson = objectMapper.writeValueAsString(result.value());
                    if (resultJson.length() > MAX_TOOL_RESULT_CHARS) {
                        resultJson = objectMapper.writeValueAsString(Map.of("truncated", true,
                                "reason", "工具结果超过上下文限制，只显示片段，不能视为完整检查",
                                "preview", resultJson.substring(0, 5000)));
                    }
                    persistStep(run, AgentStepType.TOOL_CALL, ExecutionStatus.SUCCESS,
                            request.arguments(), resultJson, result.durationMs());
                    eventService.publish(runId, "toolResult", Map.of("tool", request.name(),
                            "result", result.value(), "durationMs", result.durationMs(), "replayed", result.replayed()));
                    messages.add(ToolExecutionResultMessage.from(request, resultJson));
                    AgentMessage observation = new AgentMessage();
                    observation.setSessionId(run.getSessionId());
                    observation.setRunId(runId);
                    observation.setRole(MessageRole.TOOL);
                    observation.setContent(request.name() + " " + request.arguments() + "\n" + resultJson);
                    messageRepository.save(observation);
                }
            }
            throw new IllegalStateException("Agent 推理轮数超过限制");
        } catch (Exception e) {
            run.setStatus(AgentStatus.FAILED);
            run.setErrorMessage(e.getMessage());
            run.setElapsedMs(System.currentTimeMillis() - startedAt);
            runRepository.save(run);
            meterRegistry.counter("releaseguard.agent.runs", "outcome", "failed").increment();
            sessionRepository.findById(run.getSessionId()).ifPresent(session -> {
                session.setStatus(AgentStatus.FAILED);
                sessionRepository.save(session);
            });
            persistStep(run, AgentStepType.ERROR, ExecutionStatus.FAILED, "{}", e.getMessage(), 0L);
            eventService.publish(runId, "runFailed", Map.of("message", safeMessage(e)));
            eventService.complete(runId);
        }
    }

    private void requestConfirmation(AgentRun run, ToolExecutionRequest request) throws Exception {
        String rawToken = UUID.randomUUID().toString();
        ConfirmationRequest confirmation = new ConfirmationRequest();
        confirmation.setId(UUID.randomUUID().toString());
        confirmation.setRunId(run.getId());
        confirmation.setSessionId(run.getSessionId());
        confirmation.setToolName(request.name());
        confirmation.setArgumentsJson(request.arguments());
        confirmation.setTokenHash(sha256(rawToken));
        confirmation.setStatus(ConfirmationStatus.PENDING);
        confirmation.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        confirmationRepository.save(confirmation);

        run.setStatus(AgentStatus.WAITING_CONFIRMATION);
        run.setErrorMessage(null);
        runRepository.save(run);
        sessionRepository.findById(run.getSessionId()).ifPresent(session -> {
            session.setStatus(AgentStatus.WAITING_CONFIRMATION);
            sessionRepository.save(session);
        });
        persistStep(run, AgentStepType.CONFIRMATION, ExecutionStatus.PENDING,
                request.arguments(), confirmation.getId(), 0L);
        eventService.publish(run.getId(), "confirmation", Map.of(
                "confirmationId", confirmation.getId(), "token", rawToken,
                "tool", request.name(), "arguments", parseJson(request.arguments()),
                "expiresAt", confirmation.getExpiresAt().toString()));
        meterRegistry.counter("releaseguard.agent.runs", "outcome", "waiting_confirmation").increment();
    }

    private void completeRun(AgentRun run, String answer, long startedAt, ChatResponse response) {
        String finalAnswer = answer == null || answer.isBlank() ? "评审完成，但模型未返回文本结论。" : answer;
        run.setStatus(AgentStatus.COMPLETED);
        run.setFinalAnswer(finalAnswer);
        run.setElapsedMs(System.currentTimeMillis() - startedAt);
        if (response.tokenUsage() != null) {
            run.setInputTokens((long) response.tokenUsage().inputTokenCount());
            run.setOutputTokens((long) response.tokenUsage().outputTokenCount());
        }
        runRepository.save(run);
        meterRegistry.counter("releaseguard.agent.runs", "outcome", "completed").increment();
        AgentMessage assistant = new AgentMessage();
        assistant.setSessionId(run.getSessionId());
        assistant.setRunId(run.getId());
        assistant.setRole(MessageRole.ASSISTANT);
        assistant.setContent(finalAnswer);
        messageRepository.save(assistant);
        sessionRepository.findById(run.getSessionId()).ifPresent(session -> {
            session.setStatus(AgentStatus.COMPLETED);
            sessionRepository.save(session);
        });
        persistStep(run, AgentStepType.FINAL_ANSWER, ExecutionStatus.SUCCESS, "{}", finalAnswer,
                run.getElapsedMs());
        eventService.publish(run.getId(), "token", Map.of("text", finalAnswer));
        eventService.publish(run.getId(), "runCompleted", Map.of("runId", run.getId(),
                "elapsedMs", run.getElapsedMs()));
        eventService.complete(run.getId());
    }

    private void persistStep(AgentRun run, AgentStepType type, ExecutionStatus status,
                             String input, String output, Long durationMs) {
        AgentStep step = new AgentStep();
        step.setRunId(run.getId());
        int sequence = run.getCurrentStep() == null ? 1 : run.getCurrentStep() + 1;
        step.setSequenceNumber(sequence);
        step.setStepType(type);
        step.setStatus(status);
        step.setInputJson(input);
        step.setOutputJson(output == null ? "" : output);
        step.setDurationMs(durationMs);
        stepRepository.save(step);
        run.setCurrentStep(sequence);
        runRepository.save(run);
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode().put("raw", json);
        }
    }

    private AgentRun requireRun(String runId) {
        return runRepository.findById(runId).orElseThrow(() -> new IllegalArgumentException("Agent Run 不存在"));
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "Agent 执行失败" : message;
    }
}
