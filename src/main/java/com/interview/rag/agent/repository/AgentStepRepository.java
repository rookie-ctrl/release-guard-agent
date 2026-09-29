package com.interview.rag.agent.repository;

import com.interview.rag.agent.domain.AgentStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentStepRepository extends JpaRepository<AgentStep, Long> {

    List<AgentStep> findByRunIdOrderBySequenceNumberAsc(String runId);
}
