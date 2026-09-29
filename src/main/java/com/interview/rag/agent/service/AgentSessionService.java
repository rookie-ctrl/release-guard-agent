package com.interview.rag.agent.service;

import com.interview.rag.agent.domain.*;
import com.interview.rag.agent.repository.AgentMessageRepository;
import com.interview.rag.agent.repository.AgentRunRepository;
import com.interview.rag.agent.repository.AgentSessionRepository;
import com.interview.rag.agent.repository.ReleaseReviewRepository;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class AgentSessionService {

    private final AgentSessionRepository sessionRepository;
    private final AgentRunRepository runRepository;
    private final AgentMessageRepository messageRepository;
    private final ReleaseReviewRepository reviewRepository;
    private final AgentOutboxService outboxService;
    public AgentSessionService(AgentSessionRepository sessionRepository, AgentRunRepository runRepository,
                               AgentMessageRepository messageRepository, ReleaseReviewRepository reviewRepository,
                               AgentOutboxService outboxService) {
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
        this.messageRepository = messageRepository;
        this.reviewRepository = reviewRepository;
        this.outboxService = outboxService;
    }

    @Transactional
    public AgentSession createSession(String userId) {
        AgentSession session = new AgentSession();
        session.setId(UUID.randomUUID().toString());
        session.setStatus(AgentStatus.CREATED);
        session.setUserId(userId);
        session = sessionRepository.save(session);

        ReleaseReview review = new ReleaseReview();
        review.setId(UUID.randomUUID().toString());
        review.setSessionId(session.getId());
        review.setContextJson("{}");
        reviewRepository.save(review);
        return session;
    }

    @Transactional
    public AgentRun submitMessage(String sessionId, String content) {
        AgentSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 会话不存在"));
        if (session.getStatus() == AgentStatus.RUNNING) {
            throw new IllegalStateException("会话正在执行，请等待当前轮结束");
        }
        if (session.getStatus() == AgentStatus.WAITING_CONFIRMATION) {
            throw new IllegalStateException("会话正在等待高风险操作确认");
        }

        AgentRun run = new AgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setSessionId(sessionId);
        run.setUserMessage(content);
        run.setStatus(AgentStatus.CREATED);
        run.setCurrentStep(0);
        run.setToolCallCount(0);
        runRepository.save(run);

        AgentMessage message = new AgentMessage();
        message.setSessionId(sessionId);
        message.setRunId(run.getId());
        message.setRole(MessageRole.USER);
        message.setContent(content);
        messageRepository.save(message);

        session.setStatus(AgentStatus.RUNNING);
        sessionRepository.save(session);
        outboxService.enqueueRun(run.getId());
        return run;
    }

    public AgentSession getSession(String sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 会话不存在"));
    }

    public AgentRun getRun(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Agent 执行不存在"));
    }

    public List<AgentMessage> recentMessages(String sessionId, int limit) {
        return messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(0, limit))
                .stream().sorted(Comparator.comparing(AgentMessage::getCreatedAt)).toList();
    }

    public List<AgentMessage> getMessages(String sessionId) {
        getSession(sessionId);
        return messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, PageRequest.of(0, 100))
                .stream().sorted(Comparator.comparing(AgentMessage::getCreatedAt)).toList();
    }
}
