package com.interview.rag.agent.service;

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
    private final ApprovalOperationService approvals;
    private final AgentMessageRepository messageRepository;
    private final AgentSessionRepository sessionRepository;
    private final AgentEventService eventService;
    private final AgentOutboxService outboxService;

    public AgentConfirmationService(ConfirmationRequestRepository confirmationRepository,
                                    AgentRunRepository runRepository,
                                    ApprovalOperationService approvals,
                                    AgentMessageRepository messageRepository,
                                    AgentSessionRepository sessionRepository, AgentEventService eventService,
                                    AgentOutboxService outboxService) {
        this.confirmationRepository = confirmationRepository;
        this.runRepository = runRepository;
        this.approvals = approvals;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.eventService = eventService;
        this.outboxService = outboxService;
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
        AgentRun run = runRepository.findLockedById(confirmation.getRunId())
                .orElseThrow(() -> new IllegalArgumentException("Agent Run 不存在"));
        if (!run.getSessionId().equals(confirmation.getSessionId())) {
            throw new IllegalStateException("确认请求与执行会话不一致");
        }
        if (run.getStatus() != AgentStatus.WAITING_CONFIRMATION) {
            throw new IllegalStateException("Agent Run 当前不等待确认");
        }

        if (approved) {
            approvals.prepare(confirmation);
            confirmation.setStatus(ConfirmationStatus.APPROVED);
            confirmationRepository.save(confirmation);
            eventService.publish(run.getId(), "confirmationApproved", Map.of("approved", true));
            return run.getId();
        }
        String resultJson = "{}";
        confirmation.setStatus(ConfirmationStatus.REJECTED);
        confirmationRepository.save(confirmation);
        run.setStatus(AgentStatus.CREATED);
        run.setErrorMessage(null);
        run.setExecutionToken(null);
        run.setExecutionLeaseUntil(null);
        run.setExecutionDeadlineAt(null);
        runRepository.save(run);
        sessionRepository.findById(run.getSessionId()).ifPresent(session -> {
            session.setStatus(AgentStatus.RUNNING);
            sessionRepository.save(session);
        });
        AgentMessage observation = new AgentMessage();
        observation.setSessionId(run.getSessionId());
        observation.setRunId(run.getId());
        observation.setRole(MessageRole.TOOL);
        observation.setContent("用户拒绝创建发布审批单。不要再次请求相同审批，可继续给出评审结论。");
        messageRepository.save(observation);
        eventService.publish(run.getId(), "confirmationResolved", Map.of("approved", approved,
                "result", resultJson));
        outboxService.enqueueRun(run.getId());
        return run.getId();
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
