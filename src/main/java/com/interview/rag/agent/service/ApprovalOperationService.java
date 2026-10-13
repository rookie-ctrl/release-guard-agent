package com.interview.rag.agent.service;

import com.fasterxml.jackson.databind.*;
import com.interview.rag.agent.domain.*;
import com.interview.rag.agent.gateway.CanonicalJson;
import com.interview.rag.agent.repository.*;
import com.interview.rag.agent.tool.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class ApprovalOperationService {
    private final ApprovalOperationRepository operations;
    private final ConfirmationRequestRepository confirmations;
    private final AgentRunRepository runs;
    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final ToolInvocationRepository invocations;
    private final AgentOutboxService outbox;
    private final AgentEventService events;
    private final ToolRegistry tools;
    private final ToolExecutionService execution;
    private final ObjectMapper mapper;

    public ApprovalOperationService(ApprovalOperationRepository operations, ConfirmationRequestRepository confirmations,
            AgentRunRepository runs, AgentSessionRepository sessions, AgentMessageRepository messages,
            ToolInvocationRepository invocations, AgentOutboxService outbox, AgentEventService events,
            ToolRegistry tools, ToolExecutionService execution, ObjectMapper mapper) {
        this.operations = operations; this.confirmations = confirmations; this.runs = runs;
        this.sessions = sessions; this.messages = messages; this.invocations = invocations; this.outbox = outbox;
        this.events = events; this.tools = tools; this.execution = execution; this.mapper = mapper;
    }

    @Transactional
    public void prepare(ConfirmationRequest confirmation) {
        if (!"createReleaseApproval".equals(confirmation.getToolName())) {
            throw new ToolExecutionException("尚未实现该写工具的持久化幂等协议");
        }
        JsonNode arguments = execution.validateArguments(confirmation.getArgumentsJson(),
                tools.get(confirmation.getToolName()).definition());
        ApprovalOperation operation = new ApprovalOperation();
        operation.setId(UUID.randomUUID().toString());
        operation.setConfirmationId(confirmation.getId());
        operation.setRunId(confirmation.getRunId());
        operation.setArgumentsJson(CanonicalJson.serialize(arguments));
        operation.setStatus(ApprovalOperationStatus.PENDING);
        operation.setNextAttemptAt(LocalDateTime.now());
        operations.save(operation);
    }

    @Transactional
    public Optional<ApprovalOperation> claim(String id) {
        ApprovalOperation operation = operations.findLockedById(id).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        if (operation.getStatus() == ApprovalOperationStatus.SUCCESS || operation.getStatus() == ApprovalOperationStatus.FAILED
                || (operation.getStatus() == ApprovalOperationStatus.EXECUTING && operation.getLeaseUntil().isAfter(now))
                || (operation.getNextAttemptAt() != null && operation.getNextAttemptAt().isAfter(now))) return Optional.empty();
        operation.setStatus(ApprovalOperationStatus.EXECUTING);
        operation.setAttemptToken(UUID.randomUUID().toString());
        operation.setLeaseUntil(now.plusSeconds(60));
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operations.save(operation);
        return Optional.of(operation);
    }

    /** Local result, tool observation and resume Outbox commit atomically. */
    @Transactional
    public void finish(ApprovalOperation attempt, Object result) {
        ApprovalOperation operation = owned(attempt);
        ConfirmationRequest confirmation = confirmations.findLockedById(operation.getConfirmationId()).orElseThrow();
        AgentRun run = runs.findLockedById(operation.getRunId()).orElseThrow();
        if (confirmation.getStatus() != ConfirmationStatus.APPROVED || run.getStatus() != AgentStatus.WAITING_CONFIRMATION) {
            throw new ExecutionLostException("审批操作已不属于当前等待确认任务");
        }
        JsonNode json = mapper.valueToTree(result);
        String externalId = json.path("approvalId").asText();
        if (externalId.isBlank()) throw new IllegalStateException("外部结果缺少审批单编号，需继续对账");
        operation.setStatus(ApprovalOperationStatus.SUCCESS);
        operation.setExternalApprovalId(externalId);
        operation.setResultJson(json.toString());
        operation.setErrorMessage(null);
        operation.setLeaseUntil(null);
        operations.save(operation);
        saveInvocation(operation, ExecutionStatus.SUCCESS);
        confirmation.setStatus(ConfirmationStatus.EXECUTED);
        confirmations.save(confirmation);
        resume(run, "用户已确认并执行发布审批工具，工具结果：" + operation.getResultJson());
        events.publish(run.getId(), "confirmationResolved", Map.of("approved", true, "result", operation.getResultJson()));
    }

    @Transactional
    public void unresolved(ApprovalOperation attempt, String message, boolean definitiveFailure) {
        ApprovalOperation operation = owned(attempt);
        operation.setStatus(definitiveFailure ? ApprovalOperationStatus.FAILED : ApprovalOperationStatus.UNCERTAIN);
        operation.setErrorMessage(message);
        operation.setLeaseUntil(null);
        operation.setNextAttemptAt(LocalDateTime.now().plusSeconds(Math.min(300, 5L << Math.min(operation.getAttemptCount(), 6))));
        operations.save(operation);
        if (definitiveFailure) {
            ConfirmationRequest confirmation = confirmations.findLockedById(operation.getConfirmationId()).orElseThrow();
            AgentRun run = runs.findLockedById(operation.getRunId()).orElseThrow();
            if (confirmation.getStatus() == ConfirmationStatus.APPROVED && run.getStatus() == AgentStatus.WAITING_CONFIRMATION) {
                confirmation.setStatus(ConfirmationStatus.FAILED);
                confirmations.save(confirmation);
                saveInvocation(operation, ExecutionStatus.FAILED);
                resume(run, "发布审批系统明确拒绝执行：" + message + "。不要再次请求相同审批，说明失败原因。");
                events.publish(run.getId(), "confirmationResolved", Map.of("approved", true, "failed", true, "message", message));
            }
        } else {
            events.publish(operation.getRunId(), "approvalUncertain", Map.of("message", "审批结果尚未确认，正在核对"));
        }
    }

    private ApprovalOperation owned(ApprovalOperation attempt) {
        ApprovalOperation operation = operations.findLockedById(attempt.getId()).orElseThrow();
        if (operation.getStatus() != ApprovalOperationStatus.EXECUTING
                || !Objects.equals(operation.getAttemptToken(), attempt.getAttemptToken())
                || operation.getLeaseUntil() == null || !operation.getLeaseUntil().isAfter(LocalDateTime.now())) {
            throw new ExecutionLostException("审批执行权已失效");
        }
        return operation;
    }

    private void resume(AgentRun run, String content) {
        run.setStatus(AgentStatus.CREATED);
        run.setErrorMessage(null);
        run.setExecutionToken(null);
        run.setExecutionLeaseUntil(null);
        run.setExecutionDeadlineAt(null);
        runs.save(run);
        sessions.findById(run.getSessionId()).ifPresent(session -> {
            session.setStatus(AgentStatus.RUNNING); sessions.save(session);
        });
        AgentMessage message = new AgentMessage();
        message.setSessionId(run.getSessionId()); message.setRunId(run.getId());
        message.setRole(MessageRole.TOOL); message.setContent(content);
        messages.save(message);
        outbox.enqueueRun(run.getId());
    }

    private void saveInvocation(ApprovalOperation operation, ExecutionStatus status) {
        ToolInvocation invocation = new ToolInvocation();
        invocation.setId(operation.getId()); invocation.setRunId(operation.getRunId());
        invocation.setToolName("createReleaseApproval"); invocation.setRiskLevel(ToolRiskLevel.HIGH_RISK);
        invocation.setIdempotencyKey(CanonicalJson.hash(operation.externalKey()));
        invocation.setArgumentsJson(operation.getArgumentsJson()); invocation.setResultJson(operation.getResultJson());
        invocation.setStatus(status); invocation.setErrorMessage(operation.getErrorMessage());
        invocation.setRetryCount(operation.getAttemptCount() - 1);
        invocations.save(invocation);
    }
}
