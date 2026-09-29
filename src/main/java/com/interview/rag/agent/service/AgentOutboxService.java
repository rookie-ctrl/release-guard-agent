package com.interview.rag.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.domain.AgentOutbox;
import com.interview.rag.agent.domain.OutboxStatus;
import com.interview.rag.agent.repository.AgentOutboxRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentOutboxService {

    private final AgentOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public AgentOutboxService(AgentOutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void enqueueRun(String runId) {
        AgentOutbox outbox = new AgentOutbox();
        outbox.setId(UUID.randomUUID().toString());
        outbox.setAggregateId(runId);
        outbox.setEventType("AGENT_RUN_READY");
        try {
            outbox.setPayloadJson(objectMapper.writeValueAsString(Map.of("runId", runId)));
        } catch (Exception e) {
            throw new IllegalStateException("Agent Run 消息序列化失败", e);
        }
        outbox.setStatus(OutboxStatus.PENDING);
        outbox.setAttemptCount(0);
        outbox.setNextAttemptAt(LocalDateTime.now());
        outboxRepository.save(outbox);
    }
}
