package com.interview.rag.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.rag.agent.domain.AgentOutbox;
import com.interview.rag.agent.domain.OutboxStatus;
import com.interview.rag.agent.repository.AgentOutboxRepository;
import com.interview.rag.mq.AgentRunProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
public class AgentOutboxPublisher {

    private final AgentOutboxRepository outboxRepository;
    private final AgentRunProducer runProducer;
    private final ObjectMapper objectMapper;

    public AgentOutboxPublisher(AgentOutboxRepository outboxRepository, AgentRunProducer runProducer,
                                ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.runProducer = runProducer;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${rag.agent.outbox-poll-ms:1000}")
    public void publishPending() {
        for (AgentOutbox outbox : outboxRepository.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                OutboxStatus.PENDING, LocalDateTime.now())) {
            try {
                JsonNode payload = objectMapper.readTree(outbox.getPayloadJson());
                runProducer.send(payload.path("runId").asText());
                outbox.setStatus(OutboxStatus.PUBLISHED);
                outbox.setPublishedAt(LocalDateTime.now());
                outboxRepository.save(outbox);
            } catch (Exception e) {
                int attempts = outbox.getAttemptCount() + 1;
                outbox.setAttemptCount(attempts);
                outbox.setLastError(e.getMessage());
                outbox.setNextAttemptAt(LocalDateTime.now().plusSeconds(Math.min(60, 1L << Math.min(attempts, 6))));
                if (attempts >= 10) {
                    outbox.setStatus(OutboxStatus.DEAD);
                    log.error("Outbox {} 达到最大投递次数，标记 DEAD", outbox.getId(), e);
                }
                outboxRepository.save(outbox);
            }
        }
    }
}
