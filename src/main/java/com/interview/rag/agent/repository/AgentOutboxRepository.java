package com.interview.rag.agent.repository;

import com.interview.rag.agent.domain.AgentOutbox;
import com.interview.rag.agent.domain.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentOutboxRepository extends JpaRepository<AgentOutbox, String> {

    List<AgentOutbox> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            OutboxStatus status, LocalDateTime now);
}
