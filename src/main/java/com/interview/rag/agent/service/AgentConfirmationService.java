package com.interview.rag.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.domain.AgentRun;
import com.interview.rag.agent.domain.AgentStatus;
import com.interview.rag.agent.domain.ConfirmationRequest;
import com.interview.rag.agent.domain.ConfirmationStatus;
import com.interview.rag.agent.domain.AgentMessage;
import com.interview.rag.agent.domain.MessageRole;
import com.interview.rag.agent.repository.AgentRunRepository;
import com.interview.rag.agent.repository.AgentMessageRepository;
import com.interview.rag.agent.repository.AgentSessionRepository;
import com.interview.rag.agent.repository.ConfirmationRequestRepository;
import com.interview.rag.agent.tool.ToolExecutionService;
import com.interview.rag.agent.tool.ToolResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AgentConfirmationService {

    private final ConfirmationRequestRepository confirmationRepository;
    private final AgentRunRepository runRepository;
    private final ToolExecutionService toolExecutionService;
    private final AgentMessageRepository messageRepository;
    private final AgentSessionRepository sessionRepository;
    private final AgentEventService eventService;
    private final AgentOutboxService outboxService;
    private final ObjectMapper objectMapper;

    public AgentConfirmationService(ConfirmationRequestRepository confirmationRepository,
                                    AgentRunRepository runRepository,
                                    ToolExecutionService toolExecutionService,
                                    AgentMessageRepository messageRepository,
                                    AgentSessionRepository sessionRepository, AgentEventService eventService,
                                    AgentOutboxService outboxService, ObjectMapper objectMapper) {
        this.confirmationRepository = confirmationRepository;
        this.runRepository = runRepository;
        this.toolExecutionService = toolExecutionService;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.eventService = eventService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String decide(String confirmationId, String token, boolean approved) {
        ConfirmationRequest confirmation = confirmationRepository.findLockedById(confirmationId)
                .orElseThrow(() -> new IllegalArgumentException("确认请求不存在"));
        if (confirmation.getStatus() != ConfirmationStatus.PENDING) {
            throw new IllegalStateException("确认请求已处理");
        }
        if (confirmation.getExpiresAt().isBefore(LocalDateTime.now())) {
            confirmation.setStatus(ConfirmationStatus.EXPIRED);
            confirmationRepository.save(confirmation);
            throw new IllegalStateException("确认请求已过期");
        }
        if (!MessageDigest.isEqual(sha256(token), sha256Stored(confirmation.getTokenHash()))) {
            throw new IllegalArgumentException("确认令牌无效");
        }
        AgentRun run = runRepository.findById(confirmation.getRunId())
                .orElseThrow(() -> new IllegalArgumentException("Agent Run 不存在"));
        if (run.getStatus() != AgentStatus.WAITING_CONFIRMATION) {
            throw new IllegalStateException("Agent Run 当前不等待确认");
        }

        String resultJson = approved ? executeApprovalTool(confirmation, run) : "{}";
        confirmation.setStatus(approved ? ConfirmationStatus.EXECUTED : ConfirmationStatus.REJECTED);
        confirmationRepository.save(confirmation);
        run.setStatus(AgentStatus.RUNNING);
        run.setErrorMessage(null);
        runRepository.save(run);
        sessionRepository.findById(run.getSessionId()).ifPresent(session -> {
            session.setStatus(AgentStatus.RUNNING);
            sessionRepository.save(session);
        });
        AgentMessage observation = new AgentMessage();
        observation.setSessionId(run.getSessionId());
        observation.setRunId(run.getId());
        observation.setRole(MessageRole.TOOL);
        observation.setContent(approved
                ? "用户已确认并执行发布审批工具，工具结果：" + resultJson
                : "用户拒绝创建发布审批单。不要再次请求相同审批，可继续给出评审结论。");
        messageRepository.save(observation);
        eventService.publish(run.getId(), "confirmationResolved", Map.of("approved", approved,
                "result", resultJson));
        outboxService.enqueueRun(run.getId());
        return run.getId();
    }

    private String executeApprovalTool(ConfirmationRequest confirmation, AgentRun run) {
        try {
            ToolResult result = toolExecutionService.executeConfirmed(run.getId(), confirmation.getToolName(),
                    confirmation.getArgumentsJson());
            return objectMapper.writeValueAsString(result.value());
        } catch (Exception e) {
            throw new IllegalStateException("发布审批单创建失败: " + e.getMessage(), e);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] sha256Stored(String value) {
        try {
            return java.util.HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }
}
