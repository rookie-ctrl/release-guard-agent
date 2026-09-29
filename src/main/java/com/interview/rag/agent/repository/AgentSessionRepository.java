package com.interview.rag.agent.repository;

import com.interview.rag.agent.domain.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentSessionRepository extends JpaRepository<AgentSession, String> {
}
