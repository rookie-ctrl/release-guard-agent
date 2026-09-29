package com.interview.rag.agent.repository;

import com.interview.rag.agent.domain.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunRepository extends JpaRepository<AgentRun, String> {

    List<AgentRun> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
