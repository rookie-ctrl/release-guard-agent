package com.interview.rag.agent.repository;

import com.interview.rag.agent.domain.AgentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentEventRepository extends JpaRepository<AgentEvent, Long> {

    List<AgentEvent> findByRunIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            String runId, long sequenceNumber);

    AgentEvent findTopByRunIdOrderBySequenceNumberDesc(String runId);
}
