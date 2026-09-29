package com.interview.rag.agent.repository;

import com.interview.rag.agent.domain.AgentMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, Long> {

    List<AgentMessage> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    long countBySessionId(String sessionId);

    long countBySessionIdAndIdGreaterThan(String sessionId, Long id);

    List<AgentMessage> findBySessionIdAndIdGreaterThanOrderByIdAsc(
            String sessionId, Long id, Pageable pageable);
}
